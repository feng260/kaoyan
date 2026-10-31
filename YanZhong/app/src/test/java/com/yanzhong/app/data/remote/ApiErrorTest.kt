package com.yanzhong.app.data.remote

import java.net.SocketTimeoutException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class ApiErrorTest {

    @Test
    fun serverEnvelopeMessageIsShownVerbatim() {
        val error = httpError(401, """{"error":"INVALID_CREDENTIALS","message":"用户名或密码错误"}""")

        assertEquals("用户名或密码错误", error.apiErrorMessage())
    }

    @Test
    fun unauthorizedWithoutEnvelopeUsesCallerFallback() {
        val error = httpError(401, "")

        assertEquals("用户名或密码错误", error.apiErrorMessage(unauthorized = "用户名或密码错误"))
    }

    @Test
    fun malformedBodyNeverLeaksRawResponse() {
        val error = httpError(500, """{"oops":""")

        assertEquals("服务端暂时不可用(500)", error.apiErrorMessage())
    }

    @Test
    fun timeoutMapsToActionableMessage() {
        assertEquals("连接超时，请检查服务器地址和网络", SocketTimeoutException().apiErrorMessage())
    }

    private fun httpError(code: Int, body: String): HttpException =
        HttpException(
            Response.error<Unit>(
                code,
                body.toResponseBody("application/json".toMediaType())
            )
        )
}
