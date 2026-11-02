declare module 'app-info-parser' {
  interface ApkInfo {
    package?: string
    versionCode?: number | string
    versionName?: string
    [key: string]: unknown
  }
  export default class AppInfoParser {
    constructor(filePath: string)
    parse(): Promise<ApkInfo>
  }
}
