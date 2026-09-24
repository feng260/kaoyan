package com.yanzhong.app.data.remote

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import java.util.concurrent.TimeUnit

// ---------- DTO ----------

@Serializable
data class UserDto(val guid: String, val username: String, val email: String? = null, val createdAt: Long = 0)

@Serializable
data class DeviceDto(val id: Long, val guid: String? = null, val name: String)

@Serializable
data class DeviceFullDto(
    val id: Long,
    val guid: String? = null,
    val name: String,
    val platform: String = "",
    val lastIp: String? = null,
    val lastActiveAt: Long = 0,
    val online: Boolean = false
)

@Serializable
data class TokensDto(val access: String, val refresh: String, val expiresIn: Long)

@Serializable
data class RegisterReq(val username: String, val password: String, val email: String? = null)

/** 服务端 POST /auth/register 返回 { user: {...} }，与登录响应保持同样的信封结构。 */
@Serializable
data class RegisterResp(val user: UserDto)

@Serializable
data class LoginReq(
    val username: String,
    val password: String,
    val deviceGuid: String,
    val deviceName: String,
    val platform: String = "android",
    val rememberMe: Boolean = false
)

@Serializable
data class LoginResp(val user: UserDto, val device: DeviceDto, val tokens: TokensDto)

@Serializable
data class RefreshReq(val refresh: String)

@Serializable
data class RefreshResp(val user: UserDto, val device: DeviceDto, val tokens: TokensDto)

@Serializable
data class ForgotReq(val account: String)

@Serializable
data class ResetReq(val token: String, val newPassword: String)

@Serializable
data class ChangePasswordReq(val oldPassword: String, val newPassword: String)

@Serializable
data class DevicesResp(val devices: List<DeviceFullDto>)

@Serializable
data class SyncResult(val applied: Int = 0, val skipped: Int = 0, val serverTime: Long = 0)

@Serializable
data class SettingsResp(val settings: Map<String, kotlinx.serialization.json.JsonElement>, val serverTime: Long = 0)

@Serializable
data class PutSettingsReq(val settings: Map<String, kotlinx.serialization.json.JsonElement>)

// ---------- API 接口 ----------

interface YanzhongApi {
    @POST("api/v1/auth/register")
    suspend fun register(@Body body: RegisterReq): RegisterResp

    @POST("api/v1/auth/login")
    suspend fun login(@Body body: LoginReq): LoginResp

    @POST("api/v1/auth/refresh")
    suspend fun refresh(@Body body: RefreshReq): RefreshResp

    @POST("api/v1/auth/logout")
    suspend fun logout()

    @POST("api/v1/auth/password/forgot")
    suspend fun forgotPassword(@Body body: ForgotReq)

    @POST("api/v1/auth/password/reset")
    suspend fun resetPassword(@Body body: ResetReq)

    @POST("api/v1/auth/password/change")
    suspend fun changePassword(@Body body: ChangePasswordReq)

    @GET("api/v1/devices")
    suspend fun devices(): DevicesResp

    @DELETE("api/v1/devices/{id}")
    suspend fun revokeDevice(@Path("id") id: Long)

    @DELETE("api/v1/devices")
    suspend fun revokeAllDevices()

    @GET("api/v1/backup")
    suspend fun backup(): BackupDownload

    @POST("api/v1/backup/restore")
    suspend fun restoreBackup(@Body payload: BackupUpload): SyncResult

    /** 增量拉取:updated_at > since 的行(含墓碑);since=0 全量 */
    @GET("api/v1/sync")
    suspend fun pullChanges(@retrofit2.http.Query("since") since: Long): PullResp

    /** 批量推送:同资源脏行 + 墓碑行(isDeleted=true),服务端 LWW 幂等 upsert */
    @POST("api/v1/sync/{resource}")
    suspend fun pushResource(
        @retrofit2.http.Path("resource") resource: String,
        @Body rows: List<kotlinx.serialization.json.JsonElement>
    ): SyncResult

    @GET("api/v1/settings")
    suspend fun getSettings(): SettingsResp

    @PUT("api/v1/settings")
    suspend fun putSettings(@Body body: PutSettingsReq): SyncResult
}

// ---------- 网络装配 ----------

/**
 * 默认服务器地址:自建服务器 IP + HTTP,仅用于内测阶段。
 * 明文请求依赖 res/xml/network_security_config.xml 放行该 IP,换地址时两处要一起改。
 * 接入 HTTPS 域名后改成 "https://<域名>" 并收回明文许可;
 * 若届时仍需区分调试/正式环境,可恢复成按 BuildConfig.DEBUG 分支的写法。
 * 用户在「云同步」页保存的地址优先级更高。
 */
const val DEFAULT_SERVER_URL: String = "http://81.71.14.219:3000"

private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/** access token 注入 */
class AuthInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val token = runBlocking { TokenStore.currentAccess() } ?: return chain.proceed(chain.request())
        return chain.proceed(
            chain.request().newBuilder().header("Authorization", "Bearer $token").build()
        )
    }
}

object ApiClient {
    @Volatile private var cached: Pair<String, YanzhongApi>? = null
    private val refreshLock = Any()

    /** 服务器地址变更时自动重建 */
    fun api(): YanzhongApi {
        val server = runBlocking { TokenStore.currentServer() } ?: DEFAULT_SERVER_URL
        cached?.let { (s, api) -> if (s == server) return api }
        val client = OkHttpClient.Builder()
            .callTimeout(20, TimeUnit.SECONDS)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .addInterceptor(AuthInterceptor())
            .authenticator { _: Route?, response: Response ->
                if (response.request.url.encodedPath.contains("/auth/refresh")) return@authenticator null
                // 单飞串行化 refresh 轮换:并发 401 时避免多个请求用同一 refresh 各自轮换导致互相吊销
                synchronized(refreshLock) {
                    val refresh = runBlocking { TokenStore.currentRefresh() }
                    if (refresh == null) {
                        null
                    } else {
                        val newTokens = runBlocking {
                            runCatching {
                                val req = Request.Builder()
                                    .url("${server}/api/v1/auth/refresh")
                                    .post(
                                        okhttp3.RequestBody.create(
                                            "application/json".toMediaType(),
                                            json.encodeToString(RefreshReq.serializer(), RefreshReq(refresh))
                                        )
                                    )
                                    .header("No-Auth", "1")
                                    .build()
                                OkHttpClient().newCall(req).execute().use { resp ->
                                    if (resp.isSuccessful) json.decodeFromString(
                                        RefreshResp.serializer(), resp.body?.string() ?: ""
                                    ) else null
                                }
                            }.getOrNull()
                        }?.tokens
                        if (newTokens != null) {
                            runBlocking { TokenStore.saveTokens(newTokens.access, newTokens.refresh) }
                            response.request.newBuilder().header("Authorization", "Bearer ${newTokens.access}").build()
                        } else {
                            runBlocking { TokenStore.clearAll() }
                            null
                        }
                    }
                }
            }
            .build()
        val retrofit = Retrofit.Builder()
            .baseUrl("$server/")
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
        val api = retrofit.create(YanzhongApi::class.java)
        cached = server to api
        return api
    }

    /** 专注状态 WebSocket:登录后连接,重连成功后回调(hello + presence 恢复) */
    fun statusWs(
        token: String,
        deviceName: String,
        onEvent: (String) -> Unit,
        onOpened: () -> Unit
    ): WebSocket {
        val server = runBlocking { TokenStore.currentServer() } ?: DEFAULT_SERVER_URL
        val wsUrl = server.replaceFirst("http", "ws") + "/ws?token=" + token
        val client = OkHttpClient()
        return client.newWebSocket(
            Request.Builder().url(wsUrl).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send("""{"type":"hello","deviceName":"$deviceName"}""")
                    onOpened()
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    onEvent(text)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    onEvent("""{"type":"error","message":"${t.message ?: "连接断开"}"}""")
                }
            }
        )
    }
}
