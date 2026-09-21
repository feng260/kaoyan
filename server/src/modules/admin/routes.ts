import Router from '@koa/router'
import fs from 'fs'
import path from 'path'
import { prisma, num } from '../../shared/prisma'
import { ApiError } from '../../middlewares/error'
import { env } from '../../config/env'

/**
 * 管理端 API(鉴权:X-Admin-Token,与 APK 上传共用):
 * - GET    /admin/stats           概览统计
 * - GET    /admin/users           用户列表(含设备/任务量)
 * - DELETE /admin/users/:guid     删除用户(级联清理其全部数据)
 * - GET    /admin/releases        版本发布列表
 * - DELETE /admin/releases/:code  删除版本(DB + APK 文件)
 * 界面:GET /admin(server/public/admin.html 单文件后台)
 */
const router = new Router({ prefix: '/api/v1/admin' })

function adminGuard(ctx: any, next: () => Promise<any>) {
  const token = ctx.get('X-Admin-Token')
  if (!env.adminToken || token !== env.adminToken) {
    throw new ApiError(403, 'FORBIDDEN', '管理员令牌无效')
  }
  return next()
}

router.use(adminGuard)

router.get('/stats', async ctx => {
  const [users, devices, subjects, tasks, sessions, releases] = await Promise.all([
    prisma.user.count(),
    prisma.device.count(),
    prisma.subject.count({ where: { isDeleted: false } }),
    prisma.task.count({ where: { isDeleted: false } }),
    prisma.pomodoroSession.count({ where: { isDeleted: false } }),
    prisma.appRelease.count(),
  ])
  const focusMin = await prisma.pomodoroSession.aggregate({
    where: { isDeleted: false, valid: true },
    _sum: { durationMin: true },
  })
  ctx.body = {
    users, devices, subjects, tasks, sessions, releases,
    totalFocusMinutes: num(focusMin._sum.durationMin ?? 0),
    serverTime: Date.now(),
  }
})

router.get('/users', async ctx => {
  const users = await prisma.user.findMany({ orderBy: { createdAt: 'desc' }, take: 500 })
  const guids = users.map(u => u.guid)
  const [devices, tasks, sessions] = await Promise.all([
    guids.length ? prisma.device.groupBy({ by: ['userGuid'], where: { userGuid: { in: guids } }, _count: { _all: true } }) : [],
    guids.length ? prisma.task.groupBy({ by: ['userGuid'], where: { userGuid: { in: guids }, isDeleted: false }, _count: { _all: true } }) : [],
    guids.length ? prisma.pomodoroSession.groupBy({ by: ['userGuid'], where: { userGuid: { in: guids }, isDeleted: false }, _count: { _all: true } }) : [],
  ])
  const countOf = (rows: { userGuid: string; _count: { _all: number } }[], guid: string) =>
    rows.find(r => r.userGuid === guid)?._count._all ?? 0
  ctx.body = {
    users: users.map(u => ({
      guid: u.guid,
      username: u.username,
      email: u.email,
      createdAt: num(u.createdAt),
      devices: countOf(devices as any, u.guid),
      tasks: countOf(tasks as any, u.guid),
      sessions: countOf(sessions as any, u.guid),
    })),
  }
})

router.delete('/users/:guid', async ctx => {
  const guid = ctx.params.guid
  const user = await prisma.user.findUnique({ where: { guid } })
  if (!user) throw new ApiError(404, 'NOT_FOUND', '用户不存在')
  // 级联清理:会话→设备→业务数据→用户
  await prisma.$transaction([
    prisma.refreshToken.deleteMany({ where: { userGuid: guid } }),
    prisma.device.deleteMany({ where: { userGuid: guid } }),
    prisma.pomodoroSession.deleteMany({ where: { userGuid: guid } }),
    prisma.task.deleteMany({ where: { userGuid: guid } }),
    prisma.countdownNode.deleteMany({ where: { userGuid: guid } }),
    prisma.subject.deleteMany({ where: { userGuid: guid } }),
    prisma.dailyReview.deleteMany({ where: { userGuid: guid } }),
    prisma.weeklyReview.deleteMany({ where: { userGuid: guid } }),
    prisma.userSetting.deleteMany({ where: { userGuid: guid } }),
    prisma.user.delete({ where: { guid } }),
  ])
  ctx.status = 204
})

router.get('/releases', async ctx => {
  const releases = await prisma.appRelease.findMany({ orderBy: { versionCode: 'desc' } })
  ctx.body = {
    releases: releases.map(r => ({
      versionCode: r.versionCode,
      versionName: r.versionName,
      notes: r.notes.split('\n').filter(Boolean),
      fileSize: num(r.fileSize),
      sha256: r.sha256.slice(0, 12) + '…',
      forced: r.forced,
      createdAt: num(r.createdAt),
    })),
  }
})

router.delete('/releases/:code', async ctx => {
  const code = Number(ctx.params.code)
  const release = await prisma.appRelease.findUnique({ where: { versionCode: code } })
  if (!release) throw new ApiError(404, 'NOT_FOUND', '版本不存在')
  await prisma.appRelease.delete({ where: { versionCode: code } })
  const file = path.resolve(process.cwd(), 'releases', release.fileName)
  if (fs.existsSync(file)) fs.rmSync(file)
  ctx.status = 204
})

export default router
