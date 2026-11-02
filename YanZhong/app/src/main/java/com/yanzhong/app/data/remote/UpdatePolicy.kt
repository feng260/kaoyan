package com.yanzhong.app.data.remote

import java.util.Locale

object UpdatePolicy {
    fun shouldPrompt(latestCode: Int, forced: Boolean, skippedCode: Int, manual: Boolean): Boolean =
        manual || forced || latestCode != skippedCode

    fun formatFileSize(bytes: Long): String = when {
        bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
        bytes >= 1024L -> "${bytes / 1024} KB"
        else -> "$bytes B"
    }
}
