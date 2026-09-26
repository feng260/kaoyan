import bcrypt from 'bcryptjs'
import { prisma, num } from '../../shared/prisma'
import { ApiError } from '../../middlewares/error'
import { hub } from '../../shared/ws/Hub'
import { exportBackup } from '../sync/service'
import { planningService, type PublicPlan, type PublicProfile } from '../planning/service'
import { POLICY_EFFECTIVE_DATE, POLICY_VERSION } from '../auth/consent'

/**
 * 账号级操作:全量导出与彻底注销。
 *
 * 这两件事是「数据属于我自己」这个说法的兑现方式,所以:
 * - 导出给全部业务数据,但不含任何能拿去冒充用户的东西(密码哈希、重置 token);
 * - 注销在一个事务里删干净,中途失败就整体回滚 —— 宁可注销失败,也不要留半个账号。
 */

const now = () => Date.now()

/** 库里存的是 Date,导出给人看的是 ISO 串 */
const iso = (value: unknown): string | null => (value instanceof Date ? value.toISOString() : null)

type AnyArgs = Record<string, any>

export interface AccountDb {
  user: { findUnique(args: AnyArgs): Promise<any>; deleteMany(args: AnyArgs): Promise<any> }
  device: { deleteMany(args: AnyArgs): Promise<any> }
  refreshToken: { deleteMany(args: AnyArgs): Promise<any> }
  subject: { deleteMany(args: AnyArgs): Promise<any> }
  countdownNode: { deleteMany(args: AnyArgs): Promise<any> }
  task: { deleteMany(args: AnyArgs): Promise<any> }
  pomodoroSession: { deleteMany(args: AnyArgs): Promise<any> }
  dailyReview: { deleteMany(args: AnyArgs): Promise<any> }
  weeklyReview: { deleteMany(args: AnyArgs): Promise<any> }
  monthlyReview: { deleteMany(args: AnyArgs): Promise<any> }
  userSetting: { deleteMany(args: AnyArgs): Promise<any> }
  userProfile: { deleteMany(args: AnyArgs): Promise<any> }
  plan: { findMany(args: AnyArgs): Promise<any[]>; deleteMany(args: AnyArgs): Promise<any> }
  planStage: { deleteMany(args: AnyArgs): Promise<any> }
  planItem: { deleteMany(args: AnyArgs): Promise<any> }
  $transaction<T>(fn: (tx: AccountDb) => Promise<T>): Promise<T>
}

export interface AccountDeps {
  exportBackup: (userGuid: string) => Promise<any>
  getProfile: (userGuid: string) => Promise<PublicProfile | null>
  getActivePlan: (userGuid: string) => Promise<PublicPlan | null>
  verifyPassword: (plain: string, hash: string) => Promise<boolean>
  kickUser: (userGuid: string, reason: string) => void
}

export interface AccountExport {
  version: number
  exportedAt: number
  policy: { version: string; effectiveDate: string }
  account: {
    guid: string
    username: string
    email: string | null
    createdAt: number
    termsAcceptedAt: string | null
    privacyAcceptedAt: string | null
  }
  profile: PublicProfile | null
  plan: PublicPlan | null
  data: any
}

export interface DeleteAccountInput {
  userGuid: string
  /** 必须与用户名完全一致 —— 注销不可撤销,不能让误触把它做成一步操作 */
  confirm: string
  password: string
}

const DEFAULT_DEPS: AccountDeps = {
  exportBackup,
  getProfile: userGuid => planningService.getProfile(userGuid),
  getActivePlan: userGuid => planningService.getActivePlan(userGuid),
  verifyPassword: (plain, hash) => bcrypt.compare(plain, hash),
  kickUser: (userGuid, reason) => hub.kickUser(userGuid, reason),
}

export function createAccountService(db: AccountDb, deps: AccountDeps = DEFAULT_DEPS) {
  async function requireUser(userGuid: string) {
    const user = await db.user.findUnique({ where: { guid: userGuid } })
    if (!user) throw new ApiError(404, 'NOT_FOUND', '账号不存在')
    return user
  }

  /** 全量导出:账号信息 + 备考档案 + 当前计划 + 同步数据,一次性给全 */
  async function exportAccount(userGuid: string): Promise<AccountExport> {
    const user = await requireUser(userGuid)
    const [profile, plan, data] = await Promise.all([
      deps.getProfile(userGuid),
      deps.getActivePlan(userGuid),
      deps.exportBackup(userGuid),
    ])
    return {
      version: 1,
      exportedAt: now(),
      policy: { version: POLICY_VERSION, effectiveDate: POLICY_EFFECTIVE_DATE },
      // 逐字段挑出来,而不是把 user 整行丢出去:passwordHash / resetTokenHash 永远不该离开服务端
      account: {
        guid: user.guid,
        username: user.username,
        email: user.email ?? null,
        createdAt: num(user.createdAt),
        termsAcceptedAt: iso(user.termsAcceptedAt),
        privacyAcceptedAt: iso(user.privacyAcceptedAt),
      },
      profile,
      plan,
      data,
    }
  }

  /** 彻底注销:先验明身份,再在一个事务里删干净 */
  async function deleteAccount(input: DeleteAccountInput): Promise<{ deleted: number }> {
    const user = await requireUser(input.userGuid)
    if (String(input.confirm ?? '').trim() !== user.username) {
      throw new ApiError(400, 'CONFIRM_MISMATCH', '请输入完整用户名以确认注销')
    }
    if (!(await deps.verifyPassword(input.password, user.passwordHash))) {
      throw new ApiError(400, 'WRONG_PASSWORD', '密码错误')
    }

    const deleted = await db.$transaction(async tx => {
      // 计划的子表只挂 planId、没有 userGuid,先取出本人计划 id 再按 planId 删
      const plans = await tx.plan.findMany({ where: { userGuid: input.userGuid }, select: { id: true } })
      const planIds = plans.map((p: { id: number }) => p.id)
      const counts = await Promise.all([
        tx.planItem.deleteMany({ where: { planId: { in: planIds } } }),
        tx.planStage.deleteMany({ where: { planId: { in: planIds } } }),
        tx.plan.deleteMany({ where: { userGuid: input.userGuid } }),
        tx.userProfile.deleteMany({ where: { userGuid: input.userGuid } }),
        tx.subject.deleteMany({ where: { userGuid: input.userGuid } }),
        tx.countdownNode.deleteMany({ where: { userGuid: input.userGuid } }),
        tx.task.deleteMany({ where: { userGuid: input.userGuid } }),
        tx.pomodoroSession.deleteMany({ where: { userGuid: input.userGuid } }),
        tx.dailyReview.deleteMany({ where: { userGuid: input.userGuid } }),
        tx.weeklyReview.deleteMany({ where: { userGuid: input.userGuid } }),
        tx.monthlyReview.deleteMany({ where: { userGuid: input.userGuid } }),
        tx.userSetting.deleteMany({ where: { userGuid: input.userGuid } }),
        // 设备与刷新令牌最后删:删完这行,该账号的所有登录态即刻失效
        tx.refreshToken.deleteMany({ where: { userGuid: input.userGuid } }),
        tx.device.deleteMany({ where: { userGuid: input.userGuid } }),
        tx.user.deleteMany({ where: { guid: input.userGuid } }),
      ])
      return counts.reduce((sum, r: { count: number }) => sum + (r?.count ?? 0), 0)
    })

    // 事务提交后再踢 WS:回滚时不该把用户已经断掉的连接当成注销成功
    deps.kickUser(input.userGuid, '账号已注销')
    return { deleted }
  }

  return { exportAccount, deleteAccount }
}

export type AccountService = ReturnType<typeof createAccountService>

/** 生产实例:注入真实 Prisma 客户端与既有服务 */
export const accountService = createAccountService(prisma as unknown as AccountDb)
