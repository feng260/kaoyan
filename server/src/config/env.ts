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
  // AI 计划生成用的大模型接口(OpenAI 兼容协议)。
  // 兼容 DeepSeek / 豆包(火山方舟) / 智谱 / 硅基流动 / 通义 等;
  // 未配置 apiKey 时计划生成接口返回 AI_NOT_CONFIGURED。
  llm: {
    baseUrl: (process.env.LLM_BASE_URL ?? 'https://api.deepseek.com').replace(/\/+$/, ''),
    apiKey: process.env.LLM_API_KEY ?? '',
    model: process.env.LLM_MODEL ?? 'deepseek-flash',
    timeoutMs: Number(process.env.LLM_TIMEOUT_MS ?? 120_000),
    maxTokens: Number(process.env.LLM_MAX_TOKENS ?? 8192),
  },
}

export const smtpConfigured = (): boolean => env.smtp.host !== '' && env.smtp.user !== ''
export const adminConfigured = (): boolean => env.adminToken !== ''
/** 计划生成所需的模型密钥是否已配置 */
export const llmConfigured = (): boolean => env.llm.apiKey !== ''
