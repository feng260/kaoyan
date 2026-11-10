package com.yanzhong.app.ui.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PresetAvatarsTest {
    @Test
    fun `预设 key 与服务端白名单一致且唯一`() {
        assertEquals(
            listOf("fox", "panda", "owl", "rocket", "book", "moon", "star", "clover", "cat", "target"),
            PRESET_AVATARS.map { it.key }
        )
        assertEquals(10, PRESET_AVATARS.map { it.key }.toSet().size)
    }

    @Test
    fun `presetOf 命中与回落`() {
        assertNotNull(presetOf("fox"))
        assertNull(presetOf("dragon"))
        assertNull(presetOf(null))
    }
}