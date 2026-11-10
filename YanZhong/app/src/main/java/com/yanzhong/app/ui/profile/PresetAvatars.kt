package com.yanzhong.app.ui.profile

import androidx.compose.ui.graphics.Color

data class PresetAvatar(val key: String, val emoji: String, val label: String, val colors: List<Color>)

val PRESET_AVATARS: List<PresetAvatar> = listOf(
    PresetAvatar("fox", "🦊", "灵动", listOf(Color(0xFFF97316), Color(0xFFEA580C))),
    PresetAvatar("panda", "🐼", "沉稳", listOf(Color(0xFF64748B), Color(0xFF334155))),
    PresetAvatar("owl", "🦉", "夜读", listOf(Color(0xFF8B5CF6), Color(0xFF6D28D9))),
    PresetAvatar("rocket", "🚀", "冲刺", listOf(Color(0xFFEF4444), Color(0xFFB91C1C))),
    PresetAvatar("book", "📚", "苦读", listOf(Color(0xFF5865F2), Color(0xFF4338CA))),
    PresetAvatar("moon", "🌙", "晚自习", listOf(Color(0xFF6366F1), Color(0xFF3730A3))),
    PresetAvatar("star", "⭐", "上岸", listOf(Color(0xFFF59E0B), Color(0xFFD97706))),
    PresetAvatar("clover", "🍀", "好运", listOf(Color(0xFF22C55E), Color(0xFF15803D))),
    PresetAvatar("cat", "🐱", "陪伴", listOf(Color(0xFFEC4899), Color(0xFFBE185D))),
    PresetAvatar("target", "🎯", "目标", listOf(Color(0xFF14B8A6), Color(0xFF0F766E))),
)

fun presetOf(key: String?): PresetAvatar? = PRESET_AVATARS.firstOrNull { it.key == key }