package com.yanzhong.app.ui.plan

import com.yanzhong.app.data.remote.AdjustmentDto
import com.yanzhong.app.data.remote.DocBlockDto
import com.yanzhong.app.data.remote.PlanDto
import com.yanzhong.app.data.remote.PlanItemDto
import java.time.LocalDate

internal data class TodayItemDiff(val removed: List<PlanItemDto>, val added: List<PlanItemDto>)

internal fun compareTodayItems(draft: PlanDto, active: PlanDto?, today: LocalDate): TodayItemDiff {
    val before = active?.items.orEmpty().filter { it.planDate == today.toString() && !it.status.equals("done", ignoreCase = true) }
    val after = draft.items.filter { it.planDate == today.toString() }
    fun key(item: PlanItemDto) = Triple(item.subject, item.title, item.minutes)
    val oldCounts = before.groupingBy(::key).eachCount().toMutableMap()
    val added = after.filter { item ->
        val count = oldCounts[key(item)] ?: 0
        if (count > 0) oldCounts[key(item)] = count - 1
        count == 0
    }
    val newCounts = after.groupingBy(::key).eachCount().toMutableMap()
    val removed = before.filter { item ->
        val count = newCounts[key(item)] ?: 0
        if (count > 0) newCounts[key(item)] = count - 1
        count == 0
    }
    return TodayItemDiff(removed, added)
}

private fun escapeHtml(value: String): String = buildString {
    value.forEach { char ->
        append(when (char) {
            '&' -> "&amp;"
            '<' -> "&lt;"
            '>' -> "&gt;"
            '"' -> "&quot;"
            '\'' -> "&#39;"
            else -> char.toString()
        })
    }
}

internal fun renderDraftHtml(draft: PlanDto, diff: TodayItemDiff, today: LocalDate,
    activeCompared: Boolean = true, adjustment: AdjustmentDto? = null): String = buildString {
    fun text(value: String) = append(escapeHtml(value))
    fun itemList(items: List<PlanItemDto>, empty: String) {
        if (items.isEmpty()) { append("<p class='muted'>"); text(empty); append("</p>") }
        else {
            append("<ul class='tasks'>")
            items.forEach { item ->
                append("<li><strong>"); text(item.subject); append("</strong><span>"); text(item.title)
                append("</span><small>"); text("${item.minutes} 分钟"); append("</small></li>")
            }
            append("</ul>")
        }
    }
    append("""<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'"><style>
:root{color-scheme:light}*{box-sizing:border-box}body{margin:0;background:#f7f8f6;color:#22302a;font:14px/1.7 system-ui,sans-serif}main{max-width:700px;margin:auto;padding:20px 18px 48px}header{border-bottom:2px solid #213c35;padding:10px 0 20px}.eyebrow{font-size:11px;font-weight:700;color:#ae663c;letter-spacing:1px}h1{font-size:24px;line-height:1.35;margin:8px 0;overflow-wrap:anywhere}h2{font-size:17px;margin:28px 0 10px}h3{font-size:14px;margin:16px 0 6px}.muted,small{color:#68766f}.meta{font-size:12px;color:#68766f}section{border-bottom:1px solid #dce3dc;padding:4px 0 18px}.tasks{padding:0;margin:4px 0;list-style:none}.tasks li{display:flex;gap:8px;padding:8px 0;border-bottom:1px solid #e9ede8;align-items:baseline}.tasks strong{flex:none;color:#176458;font-size:12px}.tasks span{flex:1;overflow-wrap:anywhere}.tasks small{white-space:nowrap}.change{font-size:12px;font-weight:700;color:#ae663c;margin:12px 0 2px}.stage{display:flex;justify-content:space-between;gap:12px;padding:9px 0;border-bottom:1px solid #e9ede8}.stage span{font-weight:600}.stage small{text-align:right}.block{white-space:pre-wrap;overflow-wrap:anywhere;margin:8px 0}.note{border-left:3px solid #176458;padding:6px 10px;background:#eaf1ec}.doccard{padding:10px 0;border-bottom:1px solid #e9ede8}.tablewrap{overflow-x:auto}table{border-collapse:collapse;width:100%;font-size:12px}td,th{border-bottom:1px solid #dce3dc;text-align:left;padding:7px;min-width:85px;vertical-align:top}
</style></head><body><main><header><div class="eyebrow">DRAFT / 待确认</div><h1>""")
    text(draft.title)
    append("</h1><div class='meta'>")
    text("${draft.startDate} — ${draft.examDate} · 第 ${draft.version} 版")
    append("</div></header><section><h2>今日任务变化</h2><p class='meta'>")
    text(today.toString())
    append(" · 确认后才会替换生效计划</p>")
    if (activeCompared) {
        append("<p class='muted'>已完成任务保留；仅替换未完成任务</p>")
        append("<div class='change'>将移除 ${diff.removed.size} 项</div>")
        itemList(diff.removed, "今天没有移除的任务")
    } else {
        append("<p class='muted'>当前计划读取失败，无法确认今天会移除哪些任务</p>")
    }
    append("<div class='change'>将新增 ${diff.added.size} 项</div>")
    itemList(diff.added, "今天没有新增的任务")
    append("</section>")
    adjustment?.let { adj ->
        append("<section><h2>本次调整</h2>")
        append("<p class='note'>")
        text(adj.summary ?: "")
        append("</p>")
        if (adj.changes.isEmpty()) {
            append("<p class='muted'>没有可展示的变动明细</p>")
        } else {
            adj.changes.forEach { change ->
                append("<div class='change'>")
                text(adjustmentChangeLine(change))
                append("</div>")
            }
        }
        append("</section>")
    }
    append("<section><h2>阶段安排</h2>")
    draft.stages.sortedBy { it.sortOrder }.forEach { stage ->
        append("<div class='stage'><span>"); text(stage.name); append("</span><small>")
        text("${stage.startDate} — ${stage.endDate}"); append("</small></div>")
    }
    append("</section><section><h2>今日草稿清单</h2>")
    itemList(draft.items.filter { it.planDate == today.toString() }.sortedWith(compareBy({ it.sortOrder }, { it.id })), "今天暂无任务")
    append("</section>")
    draft.document?.let { doc ->
        append("<section><h2>"); text(doc.title.ifBlank { "全程规划" }); append("</h2>")
        if (doc.hero.subtitle.isNotBlank()) { append("<p class='block'>"); text(doc.hero.subtitle); append("</p>") }
        doc.hero.stats.forEach { stat -> append("<p class='meta'>"); text("${stat.label} · ${stat.value}"); append("</p>") }
        doc.chapters.forEach { chapter ->
            append("<h3>"); text("${chapter.no}  ${chapter.title}"); append("</h3>")
            chapter.intro?.let { append("<p class='block'>"); text(it); append("</p>") }
            chapter.blocks.forEach { block -> renderBlock(block, ::text) }
        }
        append("</section>")
    }
    append("</main></body></html>")
}

private fun StringBuilder.renderBlock(block: DocBlockDto, text: (String) -> Unit) {
    when (block.type) {
        "cards" -> block.cards.forEach { card ->
            append("<div class='doccard'><strong>"); text(card.title); append("</strong>")
            card.subtitle?.let { append("<p class='meta'>"); text(it); append("</p>") }
            card.lines.forEach { append("<p class='block'>"); text(it); append("</p>") }
            append("</div>")
        }
        "tables" -> block.tables.forEach { table ->
            append("<h3>"); text(table.title); append("</h3><div class='tablewrap'><table><thead><tr>")
            table.columns.forEach { append("<th>"); text(it); append("</th>") }
            append("</tr></thead><tbody>")
            table.rows.forEach { row ->
                append("<tr>"); table.columns.indices.forEach { index ->
                    append("<td>"); text(row.getOrElse(index) { "" }); append("</td>")
                }; append("</tr>")
            }
            append("</tbody></table></div>")
        }
        else -> block.text?.let { append(if (block.emph) "<p class='block note'>" else "<p class='block'>"); text(it); append("</p>") }
    }
}

/**
 * 用 active plan + 调整单 changes 本地拼出「确认后会长什么样」的预览计划:
 * L2 整页预览没有服务端新计划可拉,就把 changes 应用到当前计划上。
 */
internal fun buildAdjustmentPreview(active: PlanDto?, adjustment: AdjustmentDto?): PlanDto? {
    if (active == null || adjustment == null) return null
    val items = active.items.toMutableList()
    for (change in adjustment.changes) {
        val to = change.to
        when (change.kind) {
            "removed" -> items.removeAll { it.id == change.id }
            "moved", "updated" -> {
                val index = items.indexOfFirst { it.id == change.id }
                if (index >= 0 && to != null) {
                    items[index] = items[index].copy(
                        planDate = to.planDate, minutes = to.minutes.toInt(), title = to.title,
                    )
                }
            }
            "added" -> if (to != null) {
                items += PlanItemDto(
                    id = change.id, subject = change.subject, title = to.title,
                    planDate = to.planDate, minutes = to.minutes.toInt(),
                )
            }
        }
    }
    return active.copy(items = items)
}
