import dotenv from 'dotenv'
dotenv.config()

function requireEnv(name: string, fallback?: string): string {
  const v = process.env[name] ?? fallback
  if (v === undefined || v === '') {
    throw new Error(`[env] 缺少必填配置 ${name}(复制 .env.example 为 .env 并填写)`)
  }
  return v
}

export const env = {
  port: Number(process.env.PORT ?? 3000),
  nodeEnv: process.env.NODE_ENV ?? 'development',
  // 仅在确有可信反向代理(会剥离/覆写 X-Forwarded-For)时置 true;
  // 直接暴露端口时若为 true,攻击者伪造 XFF 即可绕过限流并伪造 IP
  trustProxy: (process.env.TRUST_PROXY ?? 'false') === 'true',
  databaseUrl: requireEnv('DATABASE_URL'),
  jwtSecret: requireEnv('JWT_SECRET'),
  jwtAccessTtl: process.env.JWT_ACCESS_TTL ?? '2h',
  refreshRememberDays: Number(process.env.REFRESH_REMEMBER_DAYS ?? 30),
  refreshDefaultDays: Number(process.env.REFRESH_DEFAULT_DAYS ?? 7),
  smtp: {
    host: process.env.SMTP_HOST ?? '',
    port: Number(process.env.SMTP_PORT ?? 465),
    secure: (process.env.SMTP_SECURE ?? 'true') === 'true',
    user: process.env.SMTP_USER ?? '',
    pass: process.env.SMTP_PASS ?? '',
    from: process.env.SMTP_FROM ?? '研钟 <no-reply@yanzhong.app>',
  },
  corsOrigins: (process.env.CORS_ORIGINS ?? '')
    .split(',')
    .map(s => s.trim())
    .filter(Boolean),
  adminToken: process.env.ADMIN_TOKEN ?? '',
}

export const smtpConfigured = (): boolean => env.smtp.host !== '' && env.smtp.user !== ''
export const adminConfigured = (): boolean => env.adminToken !== ''
