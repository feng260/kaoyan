package com.yanzhong.app.ui.profile

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yanzhong.app.ui.theme.AppIcons

/** 头像:自定义 base64 优先,其次预设 emoji 渐变圆,兜底学士帽。 */
@Composable
fun UserAvatar(avatarPreset: String?, avatarBase64: String?, size: Dp, modifier: Modifier = Modifier) {
    val bitmap = remember(avatarBase64) {
        if (avatarBase64.isNullOrBlank()) null else runCatching {
            val bytes = Base64.decode(avatarBase64, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }.getOrNull()
    }
    val preset = presetOf(avatarPreset)
    Box(
        modifier = modifier.size(size).clip(CircleShape)
            .background(if (preset != null) Brush.linearGradient(preset.colors) else Brush.linearGradient(listOf(Color(0xFF4338CA), Color(0xFF6D28D9)))),
        contentAlignment = Alignment.Center
    ) {
        when {
            bitmap != null -> Image(bitmap = bitmap, contentDescription = "头像", contentScale = ContentScale.Crop, modifier = Modifier.size(size).clip(CircleShape))
            preset != null -> Text(preset.emoji, fontSize = (size.value * 0.5f).sp)
            else -> Icon(AppIcons.GraduationCap, contentDescription = null, tint = Color.White, modifier = Modifier.size(size * 0.5f))
        }
    }
}