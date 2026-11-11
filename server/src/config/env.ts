import dotenv from 'dotenv'
dotenv.config()

function requireEnv(name: string, fallback?: string): string {
  const v = process.env[name] ?? fallback
  if (v === undefined || v === '') {
    throw new Error(`[env] 缺少必填配置 ${name}(复制 .env.example 为 .env 并填写)`)
  }
  return v
}

/** 取第一个非空值:用于「视觉配置缺省时回退到主配置」 */
function pick(...values: Array<string | undefined>): string {
  return values.find(v => v !== undefined && v !== '') ?? ''
}

/** 成本单价只接受正的有限数;没配或配错一律按 0(管理端不展示成本) */
function price(value: string | undefined): number {
  const n = Number(value ?? 0)
  return Number.isFinite(n) && n > 0 ? n : 0
}

/** 告警阈值只接受正的有限数;没配、置空或配错一律回退默认值(避免 0 导致天天告警) */
function positiveNumber(value: string | undefined, fallback: number): number {
  const n = Number(value ?? 0)
  return Number.isFinite(n) && n > 0 ? n : fallback
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
  // AI 用量告警:只观测不拦截;邮件发往 ALERT_EMAIL(缺省沿用发信人)
  aiAlertDailyTokens: positiveNumber(process.env.AI_ALERT_DAILY_TOKENS, 8_000_000),
  aiAlertJobTokens: positiveNumber(process.env.AI_ALERT_JOB_TOKENS, 500_000),
  // 用 pick 而非 ?? :compose 里 ALERT_EMAIL= 注入的是空串,?? 不会回退
  alertEmail: pick(process.env.ALERT_EMAIL, process.env.SMTP_FROM),
  // 邀测期静态邀请码,逗号分隔;生产未配置时注册会返回 INVITE_REQUIRED
  inviteCodes: (process.env.INVITE_CODES ?? '')
    .split(',')
    .map(s => s.trim())
    .filter(Boolean),
  // 行程调整引擎:elastic=近期弹性调整(默认);legacy=旧版整窗重排(仅回滚用)
  planAdjustEngine: process.env.PLAN_ADJUST_ENGINE === 'legacy' ? 'legacy' as const : 'elastic' as const,
  // AI 计划生成用的大模型接口(OpenAI 兼容协议)。
  // 兼容 DeepSeek / 豆包(火山方舟) / 智谱 / 硅基流动 / 通义 等;
  // 未配置 apiKey 时计划生成接口返回 AI_NOT_CONFIGURED。
  llm: {
    baseUrl: (process.env.LLM_BASE_URL ?? 'https://api.deepseek.com').replace(/\/+$/, ''),
    apiKey: process.env.LLM_API_KEY ?? '',
    model: process.env.LLM_MODEL ?? 'deepseek-flash',
    timeoutMs: Number(process.env.LLM_TIMEOUT_MS ?? 120_000),
    maxTokens: Number(process.env.LLM_MAX_TOKENS ?? 8192),
    /** 可选:按单价估算人民币成本(元/百万 token);0 表示管理端不展示成本 */
    priceInPer1M: price(process.env.LLM_PRICE_IN_PER_1M),
    priceOutPer1M: price(process.env.LLM_PRICE_OUT_PER_1M),
    /**
     * 视觉模型:识别课表图片用。默认整套沿用上面的对话模型 —— 现在主模型多半本身
     * 就是多模态(能收图片),再加一套配置纯属给部署添麻烦。只有当你想让识图单独
     * 走另一个模型时,才显式填 LLM_VISION_MODEL(地址/密钥也可各自覆盖)。
     */
    vision: {
      baseUrl: (pick(process.env.LLM_VISION_BASE_URL, process.env.LLM_BASE_URL) || 'https://api.deepseek.com').replace(/\/+$/, ''),
      apiKey: pick(process.env.LLM_VISION_API_KEY, process.env.LLM_API_KEY),
      model: pick(process.env.LLM_VISION_MODEL, process.env.LLM_MODEL) || 'deepseek-flash',
      timeoutMs: Number(pick(process.env.LLM_VISION_TIMEOUT_MS, process.env.LLM_TIMEOUT_MS) || 120_000),
      maxTokens: Number(pick(process.env.LLM_VISION_MAX_TOKENS, process.env.LLM_MAX_TOKENS) || 4096),
    },
  },
}

export const smtpConfigured = (): boolean => env.smtp.host !== '' && env.smtp.user !== ''
export const adminConfigured = (): boolean => env.adminToken !== ''
/** 计划生成所需的模型密钥是否已配置 */
export const llmConfigured = (): boolean => env.llm.apiKey !== ''
/** 课表图片识别所需的视觉模型是否已配置 */
export const visionConfigured = (): boolean => env.llm.vision.apiKey !== '' && env.llm.vision.model !== ''
