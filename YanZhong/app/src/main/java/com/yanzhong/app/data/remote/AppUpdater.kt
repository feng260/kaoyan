package com.yanzhong.app.data.remote

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.ByteString
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

@Serializable
data class LatestVersionDto(
    val versionCode: Int,
    val versionName: String,
    val notes: List<String> = emptyList(),
    val fileSize: Long = 0,
    val sha256: String = "",
    val forced: Boolean = false,
    val downloadUrl: String = ""
)

@Serializable
data class UpdateCheckResp(
    val updateAvailable: Boolean = false,
    val latest: LatestVersionDto? = null
)

/**
 * 应用内更新(自部署 OTA):启动检查 → 弹窗确认 → 下载到 files/updates →
 * FileProvider 调起系统安装器。APK 完整性 SHA-256 校验。
 */
object AppUpdater {

    /** 当前客户端版本号:构建期注入,与 build.gradle.kts versionCode/versionName 单一来源 */
    val CURRENT_VERSION_CODE: Int get() = com.yanzhong.app.BuildConfig.VERSION_CODE
    val CURRENT_VERSION_NAME: String get() = com.yanzhong.app.BuildConfig.VERSION_NAME

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** 返回 null 表示网络/服务端异常,与「已是最新版本」区分开。 */
    suspend fun checkForUpdate(): UpdateCheckResp? = withContext(Dispatchers.IO) {
        runCatching {
            val server = effectiveServerUrl()
            val req = Request.Builder()
                .url("$server/api/v1/app/latest?current=$CURRENT_VERSION_CODE")
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                json.decodeFromString(UpdateCheckResp.serializer(), resp.body?.string() ?: "")
            }
        }.getOrNull()
    }

    /** Android 8+ 覆盖安装需要「安装未知应用」权限。 */
    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.packageManager.canRequestPackageInstalls()

    fun openInstallPermissionSettings(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = android.net.Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }
    }

    /**
     * 下载 APK 并调起安装器;onProgress(0..100)。
     * 返回 true = 安装器已调起;false = 下载/校验失败(message 由调用方 Toast)。
     */
    suspend fun downloadAndInstall(
        context: Context,
        version: LatestVersionDto,
        onProgress: (Int) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            check(canInstall(context)) { "请先允许「日拱」安装未知应用" }
            val server = effectiveServerUrl()
            val dir = File(context.filesDir, "updates").apply { mkdirs() }
            // 清掉历史包,避免残留文件占空间或被误装
            dir.listFiles()?.forEach { it.deleteRecursively() }
            val file = File(dir, "yanzhong-v${version.versionCode}.apk")
            val url = "$server${version.downloadUrl}"
            if (!com.yanzhong.app.BuildConfig.DEBUG) {
                check(url.startsWith("https://")) { "更新地址必须使用 HTTPS" }
            }
            check(version.sha256.isNotBlank()) { "安装包缺少校验值，已拒绝安装" }
            val req = Request.Builder().url(url).get().build()
            client.newCall(req).execute().use { resp ->
                check(resp.isSuccessful) { "下载失败 HTTP ${resp.code}" }
                val body = resp.body ?: error("下载内容为空")
                val total = body.contentLength().coerceAtLeast(1)
                val digest = MessageDigest.getInstance("SHA-256")
                var read = 0L
                var lastPct = -1
                file.outputStream().use { out ->
                    val src = body.byteStream()
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = src.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        read += n
                        val pct = (read * 100 / total).toInt().coerceIn(0, 100)
                        if (pct != lastPct) {
                            lastPct = pct
                            withContext(Dispatchers.Main) { onProgress(pct) }
                        }
                    }
                }
                check(read > 0) { "下载内容为空" }
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                check(actual == version.sha256) { "安装包校验失败，请重试" }
                check(verifySignature(context, file)) { "安装包签名不一致，已拒绝安装" }
            }
            withContext(Dispatchers.Main) { installApk(context, file) }
        }
    }

    private fun signatureSet(info: PackageInfo): Set<ByteString> =
        info.signingInfo?.apkContentsSigners
            ?.map { ByteString.of(*it.toByteArray()) }
            ?.toSet()
            .orEmpty()

    /** 只允许与当前已安装应用同签名的 APK,防替换升级包 */
    private fun verifySignature(context: Context, apk: File): Boolean {
        val pm = context.packageManager
        val archive = pm.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: return false
        val installed = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val expected = signatureSet(installed)
        return expected.isNotEmpty() && signatureSet(archive) == expected
    }

    private fun installApk(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        // Android 8+ 需要用户先在设置开启「安装未知应用」权限,此处调起会引导
        runCatching { context.startActivity(intent) }
            .onFailure { openInstallPermissionSettings(context) }
    }
}
