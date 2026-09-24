package com.yanzhong.app.data.remote

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

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

    private val client = OkHttpClient()

    suspend fun checkForUpdate(): UpdateCheckResp = withContext(Dispatchers.IO) {
        runCatching {
            val server = effectiveServerUrl()
            val req = Request.Builder()
                .url("$server/api/v1/app/latest?current=$CURRENT_VERSION_CODE")
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                    json.decodeFromString(UpdateCheckResp.serializer(), resp.body?.string() ?: "")
                } else UpdateCheckResp()
            }
        }.getOrDefault(UpdateCheckResp())
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
            val server = effectiveServerUrl()
            val dir = File(context.filesDir, "updates").apply { mkdirs() }
            val file = File(dir, "yanzhong-v${version.versionCode}.apk")
            val req = Request.Builder().url("$server${version.downloadUrl}").get().build()
            client.newCall(req).execute().use { resp ->
                check(resp.isSuccessful) { "下载失败 HTTP ${resp.code}" }
                val body = resp.body ?: error("下载内容为空")
                val total = body.contentLength().coerceAtLeast(1)
                val digest = MessageDigest.getInstance("SHA-256")
                var read = 0L
                file.outputStream().use { out ->
                    val src = body.byteStream()
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = src.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        read += n
                        onProgress((read * 100 / total).toInt().coerceIn(0, 100))
                    }
                }
                check(read > 0) { "下载内容为空" }
                // SHA-256 完整性校验
                if (version.sha256.isNotBlank()) {
                    val actual = digest.digest().joinToString("") { "%02x".format(it) }
                    check(actual == version.sha256) { "安装包校验失败,请重试" }
                }
            }
            withContext(Dispatchers.Main) { installApk(context, file) }
        }
    }

    private fun installApk(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        // Android 8+ 需要用户先在设置开启「安装未知应用」权限,此处调起会引导
        runCatching { context.startActivity(intent) }
            .onFailure {
                // 直接打开授权页兜底
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    runCatching {
                        context.startActivity(
                            Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                                data = android.net.Uri.parse("package:${context.packageName}")
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                        )
                    }
                }
            }
    }
}
