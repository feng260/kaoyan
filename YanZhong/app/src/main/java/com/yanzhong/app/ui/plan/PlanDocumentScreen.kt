package com.yanzhong.app.ui.plan

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.yanzhong.app.data.remote.DocBlockDto
import com.yanzhong.app.data.remote.DocCardDto
import com.yanzhong.app.data.remote.DocChapterDto
import com.yanzhong.app.data.remote.DocTableDto
import com.yanzhong.app.data.remote.PlanDocumentDto
import com.yanzhong.app.ui.theme.AppCard
import com.yanzhong.app.ui.theme.AppIcons
import com.yanzhong.app.ui.theme.MetricTile
import com.yanzhong.app.ui.theme.PlanAccent
import com.yanzhong.app.ui.theme.PrimaryButton
import com.yanzhong.app.ui.theme.SegmentedChips
import com.yanzhong.app.ui.theme.SkeletonBlock
import com.yanzhong.app.ui.theme.SubPageHeader
import com.yanzhong.app.ui.theme.TextAction
import com.yanzhong.app.ui.theme.insetSurfaceColor
import kotlinx.coroutines.launch
import retrofit2.HttpException

/**
 * 「全程规划文档」页。
 *
 * 这一页是 AI 面谈的直接产物,对标参考文档《468 天考研全程作战计划》:
 * 顶部 hero(标题 + 副标题 + 科目 + 数据格)、八章正文、每章里混着
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

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            SubPageHeader(
                title = document?.title ?: "全程规划",
                meta = "规划文档 · 只读",
                onBack = { navController.popBackStack() },
                modifier = Modifier.statusBarsPadding()
            )

            when {
                loading -> Column(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 20.dp, vertical = 16.dp)
                ) {
                    SkeletonBlock(height = 24.dp)
                    Spacer(Modifier.height(16.dp))
                    repeat(2) {
                        SkeletonBlock()
                        Spacer(Modifier.height(10.dp))
                    }
                    SkeletonBlock(height = 18.dp)
                    Spacer(Modifier.height(10.dp))
                    repeat(4) {
                        SkeletonBlock()
                        Spacer(Modifier.height(10.dp))
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

@Composable
private fun DocumentBody(document: PlanDocumentDto, bottomPadding: Dp) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val tocIndex by remember {
        derivedStateOf {
            (listState.firstVisibleItemIndex - 1).coerceIn(0, document.chapters.lastIndex.coerceAtLeast(0))
        }
    }
    Column(Modifier.fillMaxSize()) {
        if (document.chapters.size > 1) {
            SegmentedChips(
                options = document.chapters.map { it.title },
                selectedIndex = tocIndex,
                onSelect = { index ->
                    scope.launch { listState.animateScrollToItem(index + 1) }
                },
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
            )
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().widthIn(max = 720.dp),
                contentPadding = PaddingValues(
                    start = 20.dp,
                    end = 20.dp,
                    top = 8.dp,
                    bottom = bottomPadding + 40.dp
                ),
                verticalArrangement = Arrangement.spacedBy(24.dp)
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
}

// ---------- Hero ----------

@Composable
private fun DocumentHero(document: PlanDocumentDto) {
    val hero = document.hero
    Column(Modifier.fillMaxWidth()) {
        Text(
            heroTitle(hero.titleLead, hero.titleAccent, hero.titleTail, MaterialTheme.colorScheme.primary),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        if (hero.subtitle.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                md(hero.subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (hero.subjects.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text(
                hero.subjects.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
        }
        if (hero.stats.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterHorizontally)
            ) {
                hero.stats.forEach { stat ->
                    MetricTile(value = stat.value, label = stat.label, compact = true)
                }
            }
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
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontFamily = FontFamily.Monospace
            )
            Spacer(Modifier.width(12.dp))
            Text(
                chapter.title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
        }

        if (!chapter.intro.isNullOrBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                md(chapter.intro),
                style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 24.sp),
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
        style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 24.sp),
        color = MaterialTheme.colorScheme.onSurface
    )
}

/** 加重段落:左侧 3dp 主色竖条 + 淡主色底,用来放"择校节奏"这类提醒 */
@Composable
private fun CalloutBlock(text: String) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Box(
            Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.primary)
        )
        Box(
            Modifier
                .weight(1f)
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f))
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Text(
                md(text),
                style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 24.sp),
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

/** 纯排版卡片:标题 + 可选副标题 + 若干要点,不再包一层带阴影的表面 */
@Composable
private fun CardBlock(card: DocCardDto) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            md(card.title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        if (!card.subtitle.isNullOrBlank()) {
            Spacer(Modifier.height(3.dp))
            Text(
                card.subtitle,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
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

/** 表格:列少时铺满宽度,列多时横向滚动(对标参考文档的 .table-wrap) */
@Composable
private fun TableBlock(table: DocTableDto) {
    if (table.columns.isEmpty()) return
    val rule = MaterialTheme.colorScheme.outlineVariant
    val headerColor = insetSurfaceColor()
    Column(Modifier.fillMaxWidth()) {
        if (table.title.isNotBlank()) {
            Text(
                table.title,
                style = MaterialTheme.typography.titleSmall,
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
                        .background(MaterialTheme.colorScheme.surface)
                        .border(1.dp, rule, RoundedCornerShape(14.dp))
                ) {
                    Row(Modifier.background(headerColor)) {
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
                color = MaterialTheme.colorScheme.onSurfaceVariant
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
    AppCard(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 420.dp)
            .padding(20.dp),
        contentPadding = PaddingValues(20.dp)
    ) {
        Box(
            Modifier
                .align(Alignment.CenterHorizontally)
                .size(44.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                AppIcons.Doc,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        detail?.let {
            Spacer(Modifier.height(6.dp))
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
        Spacer(Modifier.height(16.dp))
        PrimaryButton(
            text = if (busy) "正在重写这份规划文档…" else primaryLabel,
            onClick = onPrimary,
            enabled = !busy,
            loading = busy
        )
        Spacer(Modifier.height(4.dp))
        TextAction(
            text = "返回",
            onClick = onBack,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )
    }
}

// ---------- 小工具 ----------

/** Hero 标题:lead + accent(主色高亮) + tail,拼成一段 AnnotatedString */
private fun heroTitle(lead: String, accent: String, tail: String, accentColor: Color): AnnotatedString = buildAnnotatedString {
    append(lead)
    if (accent.isNotBlank()) {
        withStyle(SpanStyle(color = accentColor)) {
            append(accent)
        }
    }
    append(tail)
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
