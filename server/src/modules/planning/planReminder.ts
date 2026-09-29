import { prisma } from '../../shared/prisma'
import { hub } from '../../shared/ws/Hub'
import { planningService } from './service'

/**
 * 计划主动提醒(D4):体检命中(有落后/积压)且今天还没提醒过 → 推一条。
 * 每人每天最多 1 条(防打扰);任何失败只打日志,绝不影响触发它的主请求。
 */

/** 提醒去重键(user_settings):value = 最近一次发送的 YYYY-MM-DD */
const REMINDER_DAY_KEY = 'plan_reminder_day'

/** 可插拔通知出口:在线通道 = WebSocket;厂商推送只留接口,本阶段不实现(spec §9) */
export interface PlanNotifier {
  notify(userGuid: string, text: string): void
}

export const wsPlanNotifier: PlanNotifier = {
  notify(userGuid, text) {
    hub.sendToUser(userGuid, { type: 'planReminder', serverTime: Date.now(), text })
  },
}

export async function maybeNotifyPlanReminder(userGuid: string, notifier: PlanNotifier = wsPlanNotifier): Promise<void> {
  try {
    const checkup = await planningService.checkup(userGuid)
    if (!checkup.suggestion) return
    const today = new Date().toISOString().slice(0, 10)
    const row = await prisma.userSetting.upsert({
      where: { userGuid_key: { userGuid, key: REMINDER_DAY_KEY } },
      create: { userGuid, key: REMINDER_DAY_KEY, value: '', updatedAt: Date.now() },
      update: {},
    })
    if (String(row.value) === today) return // 今天已经提醒过
    await prisma.userSetting.updateMany({
      where: { userGuid, key: REMINDER_DAY_KEY },
      data: { value: today, updatedAt: Date.now() },
    })
    notifier.notify(userGuid, checkup.suggestion)
  } catch (error) {
    console.warn(`[planning] 计划提醒失败:${error instanceof Error ? error.message : String(error)}`)
  }
}
