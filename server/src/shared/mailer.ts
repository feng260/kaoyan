import nodemailer from 'nodemailer'
import { env, smtpConfigured } from '../config/env'
import { ApiError } from '../middlewares/error'

export function assertSmtpConfigured(): void {
  if (!smtpConfigured()) {
    throw new ApiError(503, 'EMAIL_NOT_CONFIGURED', '邮件服务未配置,请联系管理员')
  }
}

export function describeMailError(error: unknown): string {
  const e = error as { code?: unknown; responseCode?: unknown; message?: unknown } | null
  const code = typeof e?.code === 'string' ? e.code : 'UNKNOWN'
  const responseCode = typeof e?.responseCode === 'number' ? ` response=${e.responseCode}` : ''
  return `${code}${responseCode}`
}

export async function sendMail(input: { to: string; subject: string; text: string }): Promise<void> {
  assertSmtpConfigured()
  const transport = nodemailer.createTransport({
    host: env.smtp.host,
    port: env.smtp.port,
    secure: env.smtp.secure,
    auth: { user: env.smtp.user, pass: env.smtp.pass },
    connectionTimeout: 10_000,
  })
  try {
    await transport.sendMail({ from: env.smtp.from, to: input.to, subject: input.subject, text: input.text })
  } catch (error) {
    // nodemailer 的原始错误里带收件人地址;这里只记录脱敏错误码,再换成业务错误
    console.warn(`[mail] 发送失败:${describeMailError(error)}`)
    throw new ApiError(502, 'EMAIL_SEND_FAILED', '验证码邮件发送失败,请稍后再试')
  }
}

const TITLES: Record<'register' | 'reset', string> = {
  register: '注册验证码',
  reset: '重置密码验证码',
}

export function verificationMail(code: string, purpose: 'register' | 'reset'): { subject: string; text: string } {
  const title = TITLES[purpose]
  return {
    subject: `日拱 · ${title}`,
    text: `${title}:${code}\n10 分钟内有效,仅可使用一次。\n若非本人操作,请忽略本邮件。`,
  }
}
