package com.yanzhong.app.ui.plan

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.yanzhong.app.data.remote.ApiClient
import kotlinx.coroutines.launch
import retrofit2.HttpException
import com.yanzhong.app.data.remote.DocBlockDto
import com.yanzhong.app.data.remote.DocCardDto
import com.yanzhong.app.data.remote.DocChapterDto
import com.yanzhong.app.data.remote.DocTableDto
import com.yanzhong.app.data.remote.PlanDocumentDto
import com.yanzhong.app.ui.theme.AppIcons
import com.yanzhong.app.ui.theme.PlanAccent
import com.yanzhong.app.ui.theme.PlanAccent2
import com.yanzhong.app.ui.theme.PlanBg
import com.yanzhong.app.ui.theme.PlanTeal
import com.yanzhong.app.ui.theme.Spacing

/**
 * 「全程规划文档」页。
 *
 * 这一页是 AI 面谈的直接产物,对标参考文档《468 天考研全程作战计划》:
 * 顶部 hero(徽标 + 渐变大标题 + 科目标签 + 数据格)、八章正文、每章里混着
 * 段落 / 卡片组 / 表格组三种块。服务端已经把它们收敛成结构化数据,这里只负责把它排好看。
 *
 * 为什么不做成 WebView 直接塞 HTML:计划是账号级的活数据,还可能随档案调整重建,
 * 原生渲染才能跟其它页面共用配色与深浅色主题,而不是塞一份写死的网页。
 */
@Composable
fun PlanDocumentScreen(padding: PaddingValues, navController: NavHostController) {
    var loading by remember { mutableStateOf(true) }
    // 「网络失败」与「文档根本没生成」必须分开:前者的重试有意义,后者重试永远失败,
    // 得走服务端的补生成端点。混在一个 error 里正是"已生效却让我回去聊几句"事故的根源。
    var loadError by remember { mutableStateOf<String?>(null) }
    var missing by remember { mutableStateOf(false) }
    var document by remember { mutableStateOf<PlanDocumentDto?>(null) }
    var reloadKey by remember { mutableStateOf(0) }
    var regenerating by remember { mutableStateOf(false) }
    var regenError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun regenerate() {
        if (regenerating) return
        regenerating = true
        regenError = null
        scope.launch {
            runCatching { ApiClient.aiApi().regeneratePlanDocument().document }.fold(
                { doc ->
                    if (doc == null || doc.chapters.isEmpty()) {
                        regenError = "这次生成的文档不完整，已放弃保存，请重试"
                    } else {
                        document = doc
                        missing = false
                    }
                    regenerating = false
                },
                { e ->
                    regenError = when {
                        e is HttpException && e.code() == 404 -> "当前服务端版本较旧，不支持补生成文档"
                        e is HttpException -> "文档生成失败(${e.code()})，请稍后重试"
                        else -> "文档生成失败，请检查网络后重试"
                    }
                    regenerating = false
                },
            )
        }
    }

    LaunchedEffect(reloadKey) {
        loading = true
        loadError = null
        missing = false
        regenError = null
        // 优先走轻量端点(只回 document,不拉全量 items);仅当旧服务端没有该路由(404)时
        // 才回退全量拉取——其它异常是网络/服务端故障,必须如实报错而不是谎报"没生成"
        val light = runCatching { ApiClient.aiApi().getActivePlanDocument().document }
        var doc: PlanDocumentDto? = null
        if (light.isSuccess) {
            doc = light.getOrNull()
        } else {
            val e = light.exceptionOrNull()
            if (e is HttpException && e.code() == 404) {
                doc = runCatching { ApiClient.api().getActivePlan().plan?.document }.getOrNull()
                if (doc == null) loadError = "文档加载失败，请检查网络后重试"
            } else {
                loadError = "文档加载失败，请检查网络后重试"
            }
        }
        if (loadError == null) {
            if (doc == null || doc.chapters.isEmpty()) {
                missing = true
            } else {
                document = doc
            }
        }
        loading = false
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(PlanAccent.copy(alpha = 0.06f), PlanAccent2.copy(alpha = 0.04f),
                        MaterialTheme.colorScheme.background)
                )
            )
    ) {
        Column(Modifier.fillMaxSize()) {
            DocumentTopBar(
                title = document?.title ?: "全程规划",
                onBack = { navController.popBackStack() }
            )

            when {
                loading -> Box(
                    Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(12.dp))
                        Text("正在展开你的规划文档…", style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                document == null -> Box(
                    Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    ErrorCard(
                        message = if (missing) "这份计划还没有全程规划文档\n（生成计划时文档部分失败了，每日计划不受影响）"
                        else loadError ?: "文档暂时打不开",
                        detail = regenError,
                        busy = regenerating,
                        primaryLabel = if (missing) "重新生成文档（约 1-2 分钟）" else "重试",
                        onPrimary = if (missing) ({ regenerate() }) else ({ reloadKey++ }),
                        onBack = { navController.popBackStack() }
                    )
                }

                else -> DocumentBody(
                    document = document!!,
                    bottomPadding = padding.calculateBottomPadding()
                )
            }
        }
    }
}

/** 顶部返回栏:深层页没有底部导航,返回只能自己给 */
@Composable
private fun DocumentTopBar(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(AppIcons.ArrowBack, contentDescription = "返回",
                tint = MaterialTheme.colorScheme.onSurface)
        }
        Spacer(Modifier.width(2.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "全程规划",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                title,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun DocumentBody(document: PlanDocumentDto, bottomPadding: Dp) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().widthIn(max = 760.dp),
            contentPadding = PaddingValues(
                start = Spacing.lg,
                end = Spacing.lg,
                top = Spacing.sm,
                bottom = bottomPadding + 40.dp
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.xl)
        ) {
            item(key = "hero") { DocumentHero(document) }
            items(
                count = document.chapters.size,
                key = { index -> "ch-$index" }
            ) { index ->
                DocumentChapter(document.chapters[index])
            }
        }
    }
}

// ---------- Hero ----------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DocumentHero(document: PlanDocumentDto) {
    val hero = document.hero
    Column(
        Modifier.fillMaxWidth().padding(top = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (hero.badge.isNotBlank()) {
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.30f))
            ) {
                Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(Brush.linearGradient(listOf(PlanAccent, PlanAccent2)))
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(
                        hero.badge,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        Text(
            text = heroTitle(hero.titleLead, hero.titleAccent, hero.titleTail),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )

        if (hero.subtitle.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Text(
                md(hero.subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().widthIn(max = 560.dp)
            )
        }

        if (hero.subjects.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                hero.subjects.forEachIndexed { index, subject ->
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = chipColor(index)
                    ) {
                        Text(
                            subject,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                        )
                    }
                }
            }
        }

        if (hero.stats.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            Column(
                Modifier.fillMaxWidth().widthIn(max = 560.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                hero.stats.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { stat ->
                            StatCell(stat.label, stat.value, Modifier.weight(1f))
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun StatCell(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 1.dp,
        modifier = modifier
    ) {
        Column(
            Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1
            )
            Spacer(Modifier.height(2.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

// ---------- 章节 ----------

@Composable
private fun DocumentChapter(chapter: DocChapterDto) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                chapter.no,
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(12.dp))
            Text(
                chapter.title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
        }

        if (!chapter.intro.isNullOrBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                md(chapter.intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(14.dp))

        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            chapter.blocks.forEach { block -> DocumentBlock(block) }
        }
    }
}

@Composable
private fun DocumentBlock(block: DocBlockDto) {
    when (block.type) {
        "cards" -> block.cards.forEach { CardBlock(it) }
        "tables" -> block.tables.forEach { TableBlock(it) }
        else -> {
            val text = block.text
            if (!text.isNullOrBlank()) {
                if (block.emph) CalloutBlock(text) else ParagraphBlock(text)
            }
        }
    }
}

@Composable
private fun ParagraphBlock(text: String) {
    Text(
        md(text),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface
    )
}

/** 加重段落:参考文档里的 callout(左侧紫色竖条 + 淡紫底),用来放"择校节奏"这类提醒 */
@Composable
private fun CalloutBlock(text: String) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Box(
            Modifier
                .width(4.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp))
                .background(PlanAccent2)
        )
        Box(
            Modifier
                .weight(1f)
                .clip(RoundedCornerShape(topEnd = 14.dp, bottomEnd = 14.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f))
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Text(md(text), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

/** 层叠表面卡片:参考文档 status-item / phase 的样子——标题 + 若干要点 */
@Composable
private fun CardBlock(card: DocCardDto) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                if (!card.icon.isNullOrBlank()) {
                    Text(card.icon, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    md(card.title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
            }
            if (!card.subtitle.isNullOrBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    card.subtitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
            }
            card.lines.forEach { line ->
                Spacer(Modifier.height(6.dp))
                Text(
                    md(line),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 表格:列少时铺满宽度,列多时横向滚动(对标参考文档的 .table-wrap) */
@Composable
private fun TableBlock(table: DocTableDto) {
    if (table.columns.isEmpty()) return
    val rule = MaterialTheme.colorScheme.outlineVariant
    Column(Modifier.fillMaxWidth()) {
        if (table.title.isNotBlank()) {
            Text(
                table.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val cellWidth = maxOf(118.dp, maxWidth / table.columns.size)
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                Column(
                    Modifier
                        .width(cellWidth * table.columns.size)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .border(1.dp, rule, RoundedCornerShape(14.dp))
                ) {
                    Row(Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
                        table.columns.forEach { column ->
                            TableCell(column, cellWidth, header = true)
                        }
                    }
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(2.dp)
                            .background(PlanAccent.copy(alpha = 0.25f))
                    )
                    table.rows.forEachIndexed { rowIndex, row ->
                        Row {
                            table.columns.forEachIndexed { colIndex, _ ->
                                TableCell(row.getOrElse(colIndex) { "" }, cellWidth, header = false)
                            }
                        }
                        if (rowIndex != table.rows.lastIndex) {
                            Box(Modifier.fillMaxWidth().height(1.dp).background(rule))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TableCell(text: String, width: Dp, header: Boolean) {
    Box(Modifier.width(width).padding(horizontal = 10.dp, vertical = 9.dp)) {
        if (header) {
            Text(
                text,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                letterSpacing = 0.6.sp
            )
        } else {
            Text(md(text), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

// ---------- 状态 ----------

@Composable
private fun ErrorCard(
    message: String,
    primaryLabel: String,
    onPrimary: () -> Unit,
    onBack: () -> Unit,
    detail: String? = null,
    busy: Boolean = false,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 6.dp,
        modifier = Modifier.fillMaxWidth().widthIn(max = 420.dp).padding(24.dp)
    ) {
        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(listOf(PlanAccent, PlanAccent2))),
                contentAlignment = Alignment.Center
            ) {
                Icon(AppIcons.Doc, contentDescription = null, tint = Color.White, modifier = Modifier.size(26.dp))
            }
            Spacer(Modifier.height(14.dp))
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            detail?.let {
                Spacer(Modifier.height(6.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center
                )
            }
            Spacer(Modifier.height(18.dp))
            if (busy) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                Text(
                    "正在重写这份规划文档…",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp)
                )
            } else {
                Button(
                    onClick = onPrimary,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = PlanAccent),
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) {
                    Text(primaryLabel, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                }
            }
            TextButton(onClick = onBack) { Text("返回", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

// ---------- 小工具 ----------

/** Hero 标题:lead + accent(渐变高亮) + tail,拼成一段 AnnotatedString */
private fun heroTitle(lead: String, accent: String, tail: String): AnnotatedString = buildAnnotatedString {
    append(lead)
    if (accent.isNotBlank()) {
        withStyle(SpanStyle(brush = Brush.linearGradient(listOf(PlanAccent, PlanAccent2)))) {
            append(accent)
        }
    }
    append(tail)
}

/** 科目标签配色:按参考文档的科目色轮换,超出部分回到靛蓝 */
private fun chipColor(index: Int): Color = when (index % 5) {
    0 -> PlanAccent
    1 -> PlanAccent2
    2 -> PlanTeal
    3 -> Color(0xFFE11D48)
    else -> Color(0xFF475569)
}

/**
 * 极轻量的行内加粗解析:模型偶尔在文本里写 **重点**。
 * 与其把星号原样显示出来,不如就地渲染成加粗——不引入完整 Markdown 解析器,
 * 只处理这一种最常见的情形。
 */
private fun md(text: String): AnnotatedString {
    if (!text.contains("**")) return AnnotatedString(text)
    return buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            val start = text.indexOf("**", i)
            if (start < 0) {
                append(text.substring(i))
                break
            }
            append(text.substring(i, start))
            val end = text.indexOf("**", start + 2)
            if (end < 0) {
                append(text.substring(start))
                break
            }
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                append(text.substring(start + 2, end))
            }
            i = end + 2
        }
    }
}
