package com.yanzhong.app.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** WebDAV 客户端:PROPFIND 校验 / PUT 上传备份 JSON / GET 下载备份 JSON(Basic 鉴权) */
object WebDavClient {
    private const val BACKUP_FILE = "yanzhong-backup.json"
    private val JSON = "application/json; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun folderUrl(base: String): String = base.trim().trimEnd('/') + "/"
    private fun fileUrl(base: String): String = folderUrl(base) + BACKUP_FILE
    private fun auth(username: String, password: String): String =
        Credentials.basic(username, password)

    /** PROPFIND 根目录验证连通性与凭证(Depth 0 仅查目录自身) */
    suspend fun test(url: String, username: String, password: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url(folderUrl(url))
                    .method("PROPFIND", null)
                    .header("Authorization", auth(username, password))
                    .header("Depth", "0")
                    .build()
                client.newCall(request).execute().use { resp ->
                    if (resp.isSuccessful) "连接成功" else throw IOException("HTTP ${resp.code}")
                }
            }
        }

    /** 全量备份 JSON 上传到 WebDAV 目录 */
    suspend fun upload(url: String, username: String, password: String, json: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = json.toRequestBody(JSON)
                val request = Request.Builder()
                    .url(fileUrl(url))
                    .header("Authorization", auth(username, password))
                    .put(body)
                    .build()
                client.newCall(request).execute().use { resp ->
                    if (resp.isSuccessful) "已上传备份" else throw IOException("HTTP ${resp.code}")
                }
            }
        }

    /** 从 WebDAV 目录下载全量备份 JSON */
    suspend fun download(url: String, username: String, password: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url(fileUrl(url))
                    .header("Authorization", auth(username, password))
                    .get()
                    .build()
                client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                    resp.body?.string() ?: throw IOException("空响应")
                }
            }
        }
}