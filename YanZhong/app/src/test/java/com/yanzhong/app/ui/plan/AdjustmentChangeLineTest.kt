package com.yanzhong.app.ui.plan

import com.yanzhong.app.data.remote.AdjustmentChangeDto
import com.yanzhong.app.data.remote.AdjustmentFieldDto
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdjustmentChangeLineTest {

    @Test
    fun `附带说明时追加在变动行末尾`() {
        val change = AdjustmentChangeDto(
            kind = "updated",
            id = 1,
            subject = "英语",
            title = "整理",
            from = AdjustmentFieldDto("2026-11-10", 60, "整理"),
            to = AdjustmentFieldDto("2026-11-10", 45, "整理"),
            note = "缩短 60→45 分钟（可让）"
        )
        assertTrue(adjustmentChangeLine(change).contains("缩短 60→45 分钟（可让）"))
    }

    @Test
    fun `没有说明时保持原格式`() {
        val change = AdjustmentChangeDto(
            kind = "moved",
            id = 1,
            subject = "数学",
            title = "真题",
            from = AdjustmentFieldDto("2026-11-10", 60, "真题"),
            to = AdjustmentFieldDto("2026-11-11", 60, "真题")
        )
        val line = adjustmentChangeLine(change)
        assertTrue(line.contains("挪动"))
        assertFalse(line.contains("null"))
    }
}