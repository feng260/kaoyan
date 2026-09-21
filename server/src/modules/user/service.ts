import { prisma, num } from '../../shared/prisma'
import { ApiError } from '../../middlewares/error'
import { hub } from '../../shared/ws/Hub'

const now = () => Date.now()

export async function listDevices(userGuid: string) {
  const devices = await prisma.device.findMany({
    where: { userGuid },
    orderBy: { lastActiveAt: 'desc' },
  })
  const online = new Set(hub.onlineDevices(userGuid).map(d => d.deviceId))
  // 当前设备(由 access token 的 deviceId 标记)不显示「登出」按钮
  return devices.map((d: { id: number; deviceGuid: string; name: string; platform: string; lastIp: string | null; lastActiveAt: bigint }) => ({
    id: d.id,
    guid: d.deviceGuid,
    name: d.name,
    platform: d.platform,
    lastIp: d.lastIp,
    lastActiveAt: num(d.lastActiveAt),
    online: online.has(d.id),
  }))
}

/** 强制登出指定设备:吊销 refresh + 踢 WS */
export async function revokeDevice(userGuid: string, deviceId: number) {
  const device = await prisma.device.findFirst({ where: { id: deviceId, userGuid } })
  if (!device) throw new ApiError(404, 'NOT_FOUND', '设备不存在')
  await prisma.refreshToken.updateMany({
    where: { userGuid, deviceId, revokedAt: null },
    data: { revokedAt: now() },
  })
  hub.kick(userGuid, deviceId, '强制登出')
}

/** 全部登出(含当前设备) */
export async function revokeAll(userGuid: string) {
  await prisma.refreshToken.updateMany({
    where: { userGuid, revokedAt: null },
    data: { revokedAt: now() },
  })
  hub.kickUser(userGuid, '全部设备登出')
}
