import WebSocket from 'ws'
import { verifyAccessToken } from '../../shared/jwt'
import { hub, PeerStatus } from '../../shared/ws/Hub'

/**
 * 专注状态 WebSocket(/ws?token=<access>):
 * C→S hello{deviceName} / status{...} / stop{}
 * S→C presence / peerStatus / kicked
 * access token 过期时 4001 关闭,客户端刷新后重连。
 */
export function setupStatusWs(server: import('http').Server) {
  const wss = new WebSocket.Server({ server, path: '/ws' })
  hub.startHeartbeat()

  wss.on('connection', (ws, req) => {
    const url = new URL(req.url ?? '/ws', 'http://localhost')
    const token = url.searchParams.get('token') ?? ''
    const payload = verifyAccessToken(token)
    if (!payload) {
      ws.close(4001, 'unauthorized')
      return
    }
    const { sub: userGuid, did: deviceId } = payload
    const conn = hub.add(ws, userGuid, deviceId, `设备#${deviceId}`)

    ws.on('message', raw => {
      let msg: any
      try { msg = JSON.parse(String(raw)) } catch { return }
      switch (msg.type) {
        case 'hello': {
          if (typeof msg.deviceName === 'string' && msg.deviceName) {
            hub.rename(deviceId, msg.deviceName.slice(0, 64))
            conn.deviceName = msg.deviceName.slice(0, 64)
          }
          // 重连即全量重拉:回推 presence(self 标记让客户端过滤自己)
          hub.send(ws, {
            type: 'presence',
            serverTime: Date.now(),
            devices: hub.onlineDevices(userGuid).map(d => ({ ...d, online: true, self: d.deviceId === deviceId })),
          })
          break
        }
        case 'status': {
          const status: PeerStatus = {
            phase: ['focus', 'break', 'idle'].includes(msg.phase) ? msg.phase : 'idle',
            remainMs: typeof msg.remainMs === 'number' ? msg.remainMs : undefined,
            taskId: typeof msg.taskId === 'number' ? msg.taskId : undefined,
            taskTitle: typeof msg.taskTitle === 'string' ? msg.taskTitle : undefined,
            planName: typeof msg.planName === 'string' ? msg.planName : undefined,
            sessionGuid: typeof msg.sessionGuid === 'string' ? msg.sessionGuid.slice(0, 64) : undefined,
            following: typeof msg.following === 'boolean' ? msg.following : undefined,
            paused: typeof msg.paused === 'boolean' ? msg.paused : undefined,
          }
          hub.setStatus(deviceId, status)
          break
        }
        case 'stop': {
          hub.setStatus(deviceId, { phase: 'idle' })
          break
        }
      }
    })
  })
  return wss
}
