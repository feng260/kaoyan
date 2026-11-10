package com.yanzhong.app.ui.profile

internal fun codePointLength(s: String): Int = s.codePointCount(0, s.length)

internal fun validateNickname(s: String?): String? =
    if (s != null && codePointLength(s.trim()) > 32) "昵称最多 32 个字符" else null

internal fun validateSignature(s: String?): String? =
    if (s != null && codePointLength(s.trim()) > 100) "签名最多 100 个字符" else null

internal fun validateGender(s: String?): String? =
    if (s != null && s !in listOf("male", "female", "other", "secret")) "性别取值非法" else null

internal fun validateBirthday(s: String?, today: String): String? {
    if (s.isNullOrBlank()) return null
    if (!Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(s)) return "生日格式应为 YYYY-MM-DD"
    val y = s.substring(0, 4).toInt()
    if (y < 1900 || s > today) return "生日年份或范围非法"
    return runCatching {
        val d = java.time.LocalDate.parse(s)
        if (d.toString() != s) "生日不是真实日期" else null
    }.getOrElse { "生日不是真实日期" }
}

internal fun avatarConflict(preset: String?, custom: String?): String? =
    if (!preset.isNullOrBlank() && !custom.isNullOrBlank()) "预设头像和自定义头像只能选一个" else null