import WebSocket from 'ws'

export interface PeerStatus {
  phase: 'focus' | 'break' | 'idle'
  remainMs?: number
  taskId?: number
  taskTitle?: string
  planName?: string
  /** 本次专注链路的稳定标识:跟随端用它识别「还是同一场专注」 */
  sessionGuid?: string
  /** true = 该端处于跟随状态(镜像他端,不产生自己的 session) */
  following?: boolean
  /** true = 主端暂停中:跟随端冻结倒计时而不是倒数到 0 */
  paused?: boolean
}

interface Conn {
  ws: WebSocket
  userGuid: string
  deviceId: number
  deviceName: string
  alive: boolean
  lastStatus?: PeerStatus
}

/**
 * WebSocket 连接中心:按用户分组管理连接;
 * - presence 快照(全部设备 + 在线状态 + 最近专注状态)
 * - peerStatus 广播(某端状态变更推送其他端)
 * - kick(设备被强制登出时断开其连接)
 * 重连恢复策略:客户端重连后发送 hello,服务端即回推 presence 全量。
 */
class WsHub {
  private conns = new Map<number, Conn>() // key: deviceId
  private timer: NodeJS.Timeout | null = null

  startHeartbeat() {
    if (this.timer) return
    this.timer = setInterval(() => {
      const now = Date.now()
      for (const [deviceId, conn] of this.conns) {
        if (!conn.alive) {
          conn.ws.terminate()
          this.remove(deviceId, 'ping-timeout')
          continue
        }
        conn.alive = false
        try { conn.ws.ping() } catch { /* 已断开,下轮清理 */ }
      }
    }, 30_000)
    this.timer.unref()
  }

  add(ws: WebSocket, userGuid: string, deviceId: number, deviceName: string) {
    const conn: Conn = { ws, userGuid, deviceId, deviceName, alive: true }
    this.conns.set(deviceId, conn)
    ws.on('pong', () => { conn.alive = true })
    ws.on('close', () => this.remove(deviceId, 'closed'))
    ws.on('error', () => this.remove(deviceId, 'error'))
    return conn
  }

  /** 设备主动改名(hello 时机) */
  rename(deviceId: number, name: string) {
    const conn = this.conns.get(deviceId)
    if (conn) conn.deviceName = name
  }

  private remove(deviceId: number, reason: string) {
    const conn = this.conns.get(deviceId)
    if (!conn) return
    this.conns.delete(deviceId)
    this.broadcastPresence(conn.userGuid, `peer-offline:${reason}`)
  }

  setStatus(deviceId: number, status: PeerStatus) {
    const conn = this.conns.get(deviceId)
    if (!conn) return
    conn.lastStatus = status
    // 推送给同用户其他在线设备
    this.sendToUser(conn.userGuid, {
      type: 'peerStatus',
      serverTime: Date.now(),
      deviceId,
      deviceName: conn.deviceName,
      status,
    }, deviceId)
  }

  /** 断开指定设备(强制登出) */
  kick(userGuid: string, deviceId: number, reason: string) {
    const conn = this.conns.get(deviceId)
    if (!conn) return
    this.send(conn.ws, { type: 'kicked', serverTime: Date.now(), reason })
    conn.ws.close(4001, reason)
    this.remove(deviceId, 'kicked')
  }

  kickUser(userGuid: string, reason: string) {
    for (const [, conn] of this.conns) {
      if (conn.userGuid === userGuid) this.kick(userGuid, conn.deviceId, reason)
    }
  }

  send(ws: WebSocket, payload: object) {
    if (ws.readyState === WebSocket.OPEN) {
      ws.send(JSON.stringify(payload))
    }
  }

  sendToUser(userGuid: string, payload: object, exceptDeviceId?: number) {
    for (const [deviceId, conn] of this.conns) {
      if (conn.userGuid === userGuid && deviceId !== exceptDeviceId) {
        this.send(conn.ws, payload)
      }
    }
  }

  /** presence 快照:该用户全部登记设备(DB)由调用方合并,这里提供在线部分 */
  onlineDevices(userGuid: string) {
    return [...this.conns.values()]
      .filter(c => c.userGuid === userGuid)
      .map(c => ({ deviceId: c.deviceId, deviceName: c.deviceName, lastStatus: c.lastStatus }))
  }

  private broadcastPresence(userGuid: string, _reason: string) {
    // 按接收者逐个打 self 标记:客户端据此把自己的设备从 peer 列表中过滤掉
    for (const [deviceId, conn] of this.conns) {
      if (conn.userGuid !== userGuid) continue
      this.send(conn.ws, {
        type: 'presence',
        serverTime: Date.now(),
        devices: this.onlineDevices(userGuid).map(d => ({ ...d, online: true, self: d.deviceId === deviceId })),
      })
    }
  }
}

export const hub = new WsHub()
