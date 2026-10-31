package com.yanzhong.app.data.remote

import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import retrofit2.HttpException

/**
 * 把接口异常翻译成考生能看懂的一句话。
 * 服务端错误信封是 { error, message },message 已是面向用户的中文说明,优先原样透出;
 * 解析不出来时按状态码兜底 —— 不要把原始 JSON body 显示到界面上。
 */
fun Throwable.apiErrorMessage(unauthorized: String = "登录已过期，请重新登录"): String = when (this) {
    is SocketTimeoutException -> "连接超时，请检查服务器地址和网络"
    is ConnectException -> "无法连接服务器，请确认服务已启动"
    is UnknownHostException -> "找不到服务器，请检查地址"
    is HttpException -> serverMessage() ?: when (code()) {
        401, 403 -> unauthorized
        404 -> "请求的内容不存在，请刷新后重试"
        429 -> "操作太频繁，请稍后再试"
        in 500..599 -> "服务端暂时不可用(${code()})"
        else -> "请求失败(${code()})"
    }
    else -> message ?: "未知错误"
}

/** 解析 { error, message } 信封;任何一步失败都返回 null,交给调用方按状态码兜底。 */
private fun HttpException.serverMessage(): String? {
    val body = runCatching { response()?.errorBody()?.string() }.getOrNull()
        ?.takeIf { it.isNotBlank() } ?: return null
    return runCatching {
        Json.parseToJsonElement(body).jsonObject["message"]?.jsonPrimitive?.contentOrNull
    }.getOrNull()?.takeIf { it.isNotBlank() }
}
