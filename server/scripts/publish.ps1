# 研钟一键发布脚本
# 用法:
#   .\publish.ps1 -Notes "新增云同步,修复时段统计"           # 发布当前版本
#   .\publish.ps1 -Notes "xxx" -Bump                        # 自动 versionCode+1 并发布
#   .\publish.ps1 -Notes "安全修复" -Bump -Forced            # 强更版本
#   .\publish.ps1 -Notes "xxx" -Server "http://192.168.1.5:3000"
# Notes 用逗号分隔多条更新说明,弹窗逐条展示。
param(
  [Parameter(Mandatory = $true)][string]$Notes,
  [switch]$Bump,     # 自动 versionCode +1、versionName 末位 +1
  [switch]$Forced,   # 强制更新(低版本必须升级)
  [string]$Server = "http://localhost:3000",
  [switch]$DebugApk  # 默认打 release;开发期可用 debug 包(签名一致才能覆盖安装)
)
$ErrorActionPreference = "Stop"

$root = Split-Path $PSScriptRoot -Parent            # server/
$solution = Split-Path $root -Parent                # 项目根
$gradleFile = Join-Path $solution "YanZhong\app\build.gradle.kts"
$updaterFile = Join-Path $solution "YanZhong\app\src\main\java\com\yanzhong\app\data\remote\AppUpdater.kt"

# ---- 1. 读当前版本 ----
$gradle = Get-Content $gradleFile -Raw -Encoding UTF8
if ($gradle -notmatch 'versionCode\s*=\s*(\d+)') { throw "build.gradle.kts 未找到 versionCode" }
$code = [int]$Matches[1]
if ($gradle -notmatch 'versionName\s*=\s*"([^"]+)"') { throw "build.gradle.kts 未找到 versionName" }
$name = $Matches[1]

# ---- 2. 可选递增 ----
if ($Bump) {
  $code++
  if ($name -match '^(.*?)(\d+)$') { $name = $Matches[1] + ([int]$Matches[2] + 1) }
  else { $name = "$name.1" }
  $gradle = $gradle -replace 'versionCode\s*=\s*\d+', "versionCode = $code"
  $gradle = $gradle -replace "versionName\s*=\s*`"[^`"]+`"", "versionName = `"$name`""
  Set-Content $gradleFile $gradle -Encoding UTF8
}

# ---- 3. 同步 AppUpdater 常量(App 端版本比较依据) ----
$updater = Get-Content $updaterFile -Raw -Encoding UTF8
$updater = $updater -replace 'CURRENT_VERSION_CODE = \d+', "CURRENT_VERSION_CODE = $code"
$updater = $updater -replace 'CURRENT_VERSION_NAME = "[^"]+"', "CURRENT_VERSION_NAME = `"$name`""
Set-Content $updaterFile $updater -Encoding UTF8
Write-Host "▶ 发布版本 $name (versionCode $code)"

# ---- 4. 打 APK ----
Push-Location (Join-Path $solution "YanZhong")
try {
  if ($DebugApk) {
    & .\gradlew.bat :app:assembleDebug -q --console=plain
    if ($LASTEXITCODE -ne 0) { throw "assembleDebug 失败" }
    $apk = "app\build\outputs\apk\debug\app-debug.apk"
  } else {
    & .\gradlew.bat :app:assembleRelease -q --console=plain
    if ($LASTEXITCODE -ne 0) { throw "assembleRelease 失败" }
    # 已配置 signingConfigs.release → 输出为已签名 app-release.apk
    $apk = "app\build\outputs\apk\release\app-release.apk"
  }
} finally { Pop-Location }
if (-not (Test-Path $apk)) { throw "APK 未生成: $apk" }
$apkItem = Get-Item $apk
Write-Host ("▶ APK 就绪 {0:N1} MB" -f ($apkItem.Length / 1MB))

# ---- 5. 读 ADMIN_TOKEN 并上传 ----
$envFile = Join-Path $root ".env"
if (-not (Test-Path $envFile)) { throw "缺少 server/.env(参照 .env.example 配置 ADMIN_TOKEN)" }
$token = (Get-Content $envFile | Where-Object { $_ -match '^ADMIN_TOKEN=(.+)$' } |
  ForEach-Object { ($_ -replace '^ADMIN_TOKEN=', '').Trim() } | Select-Object -First 1)
if (-not $token) { throw "server/.env 未配置 ADMIN_TOKEN" }

$noteLines = ($Notes -split '[,,;；]' | ForEach-Object { $_.Trim() } | Where-Object { $_ }) -join "`n"
$encodedNotes = [uri]::EscapeDataString($noteLines)
$url = "$Server/api/v1/admin/app/upload?versionCode=$code&versionName=$([uri]::EscapeDataString($name))&notes=$encodedNotes"
if ($Forced) { $url += "&forced=true" }

Write-Host "▶ 上传到 $Server ..."
Invoke-RestMethod -Method Put -Uri $url -Headers @{ "X-Admin-Token" = $token } -InFile $apkItem.FullName | Out-Null
Write-Host ""
Write-Host "✔ 发布完成:版本 $name 已上架,所有已配置服务器的设备下次启动会收到更新弹窗" -ForegroundColor Green
