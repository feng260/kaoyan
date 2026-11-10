package com.yanzhong.app.data.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream

/** 相册图 → 缩放 → JPEG → 裸 base64;失败返回 null。课表用 1600/82,头像用 512/85。 */
internal fun compressImageToBase64(context: Context, uri: Uri, maxSide: Int, quality: Int): String? {
    val raw = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
    val decoded = BitmapFactory.decodeByteArray(raw, 0, raw.size) ?: return null
    val longest = maxOf(decoded.width, decoded.height)
    val scaled = if (longest <= maxSide) decoded else {
        val ratio = maxSide.toFloat() / longest
        Bitmap.createScaledBitmap(
            decoded,
            (decoded.width * ratio).toInt().coerceAtLeast(1),
            (decoded.height * ratio).toInt().coerceAtLeast(1),
            true
        )
    }
    val out = ByteArrayOutputStream()
    val ok = scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
    if (scaled !== decoded) scaled.recycle()
    decoded.recycle()
    if (!ok) return null
    return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
}