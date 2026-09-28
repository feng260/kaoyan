package com.yanzhong.app.ui.mine

/** 头部副标题:最多 4 个科目名以 " + " 连接,超出追加 "等 N 科";无科目给占位文案 */
fun formatSubjectSubtitle(names: List<String>): String {
    val cleaned = names.map { it.trim() }.filter { it.isNotEmpty() }
    if (cleaned.isEmpty()) return "尚未设置科目"
    val head = cleaned.take(4).joinToString(" + ")
    return if (cleaned.size > 4) "$head 等 ${cleaned.size} 科" else head
}
