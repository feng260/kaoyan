import { smtpConfigured } from '../config/env'

/** 健康检查负载:客户端据此判断「忘记密码」能否发邮件,避免把用户带进死路 */
export function healthPayload(now: number = Date.now(), dbOk = true) {
  return {
    ok: dbOk,
    serverTime: now,
    features: { emailReset: smtpConfigured() },
  }
}
