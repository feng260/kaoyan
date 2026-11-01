/**
 * user_setting.value 多数是 JSON.stringify 的结果,但 plan_reminder_day 这类由服务端自己写的行存的是裸字符串;
 * 解不开就按原文返回,否则一条设置能让整个备份/导出 500。
 */
export function parseSettingsRows(rows: Array<{ key: string; value: string }>): Record<string, unknown> {
  const settings: Record<string, unknown> = {}
  for (const row of rows) {
    try { settings[row.key] = JSON.parse(row.value) } catch { settings[row.key] = row.value }
  }
  return settings
}

/**
 * 恢复包里的 settings 只有真的带了键才覆盖服务端设置。
 * 旧备份没有 settings 字段,客户端 encodeDefaults 会补成 {},若照旧先删后插,
 * 等于用一份"没有设置"的备份清空用户所有设置。
 */
export function settingsEntries(value: unknown): Array<[string, unknown]> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return []
  return Object.entries(value as Record<string, unknown>)
}
