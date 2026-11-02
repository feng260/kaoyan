import AppInfoParser from 'app-info-parser'

export const EXPECTED_APP_PACKAGE = 'com.yanzhong.app'

export interface ApkMeta {
  packageName: string
  versionCode: number
  versionName: string
}

export function normalizeApkMeta(raw: {
  package?: unknown
  versionCode?: unknown
  versionName?: unknown
}): ApkMeta | null {
  const packageName = typeof raw.package === 'string' ? raw.package.trim() : ''
  const versionCode = Number(raw.versionCode)
  const versionName = typeof raw.versionName === 'string' ? raw.versionName.trim() : ''
  if (!packageName) return null
  if (!Number.isSafeInteger(versionCode) || versionCode <= 0) return null
  return { packageName, versionCode, versionName: versionName || `build-${versionCode}` }
}

export async function parseApkMeta(filePath: string): Promise<ApkMeta | null> {
  try {
    const raw = await new AppInfoParser(filePath).parse()
    return normalizeApkMeta(raw)
  } catch {
    return null
  }
}

/** 发布前置校验:必须是研钟,且版本号严格大于线上最新(等于会导致老设备收不到更新)。 */
export function validateRelease(meta: ApkMeta, latestVersionCode: number | null): string | null {
  if (meta.packageName !== EXPECTED_APP_PACKAGE) {
    return `安装包不是研钟(${meta.packageName})`
  }
  if (latestVersionCode !== null && meta.versionCode <= latestVersionCode) {
    return `versionCode ${meta.versionCode} 必须大于当前最新 ${latestVersionCode}`
  }
  return null
}

/** query 开关是字符串,Boolean('false') === true,必须显式解析。 */
export function parseBoolFlag(raw: unknown): boolean {
  return raw === true || raw === 1 || raw === 'true' || raw === '1'
}
