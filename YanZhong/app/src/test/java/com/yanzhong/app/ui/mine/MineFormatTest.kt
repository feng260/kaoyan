package com.yanzhong.app.ui.mine

import org.junit.Assert.assertEquals
import org.junit.Test

class MineFormatTest {

    @Test fun subjectSubtitle_empty() {
        assertEquals("尚未设置科目", formatSubjectSubtitle(emptyList()))
        assertEquals("尚未设置科目", formatSubjectSubtitle(listOf("  ", "")))
    }

    @Test fun subjectSubtitle_fourOrFewer() {
        assertEquals("数学", formatSubjectSubtitle(listOf("数学")))
        assertEquals("数学 + 英语 + 政治 + 专业课",
            formatSubjectSubtitle(listOf("数学", "英语", "政治", "专业课")))
    }

    @Test fun subjectSubtitle_moreThanFour() {
        assertEquals("数学 + 英语 + 政治 + 专业课 等 5 科",
            formatSubjectSubtitle(listOf("数学", "英语", "政治", "专业课", "第二外语")))
    }
}
