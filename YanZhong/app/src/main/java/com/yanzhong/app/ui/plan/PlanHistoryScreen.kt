package com.yanzhong.app.ui.plan

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.yanzhong.app.data.remote.ApiClient
import com.yanzhong.app.data.remote.PlanDto
import com.yanzhong.app.data.remote.PlanItemDto
import com.yanzhong.app.data.remote.PlanSummaryDto
import com.yanzhong.app.data.remote.PlanStageDto
import com.yanzhong.app.ui.theme.AppIcons

/**
 * 「历史计划」页:按版本倒序保留每一份生成过的计划,供只读回看。
 *
 * 为什么要单独一页:重新生成会新建一份计划、旧的归档(status=archived)。
 * 归档只是"不再生效",不是"删掉"——用户当初打过的卡不该凭空消失,
 * 所以给一个能翻旧账的地方,而不是把归档悄悄藏在数据库里。
 *
 * 列表只拉摘要(服务端有意不返回 items),点进某一条才取完整明细:
 * 一份计划动辄上千条计划项,列表接口把明细带上的话,打开这一页就要卡半天。
 */
@Composable
fun PlanHistoryScreen(padding: PaddingValues, navController: NavHostController) {
    val colors = MaterialTheme.colorScheme
    /** null = 正在加载;非 null = 已拿到结果(可能为空列表) */
    var plans by remember { mutableStateOf<List<PlanSummaryDto>?>(null) }
    var listError by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableIntStateOf(0) }
    /** 当前展开的历史计划明细;null 表示停在列表 */
    var detail by remember { mutableStateOf<PlanDto?>(null) }
    var detailError by remember { mutableStateOf<String?>(null) }
    var pendingDetailId by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(reloadKey) {
        listError = null
        plans = null
        runCatching { ApiClient.api().getPlanHistory() }.fold(
            { plans = it.plans },
            { listError = it.shortMessage("历史计划没拉到") }
        )
    }

    // 取某一条的完整明细;打开详情期间列表暂不卸载(返回时不必重新拉列表)
    LaunchedEffect(pendingDetailId) {
        val id = pendingDetailId ?: return@LaunchedEffect
        detailError = null
        runCatching { ApiClient.api().getPlanById(id).plan }.fold(
            { p ->
                if (p == null) detailError = "这一版计划已经找不到了"
                else detail = p
            },
            { detailError = it.shortMessage("这一版计划没打开") }
        )
        pendingDetailId = null
    }

    // 详情态优先接管返回:先回列表,再退出本页
    BackHandler(enabled = detail != null) { detail = null }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(colors.primaryContainer.copy(alpha = 0.45f), colors.background, colors.background)
                )
            )
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            HistoryTopBar(
                title = if (detail != null) "计划详情" else "历史计划",
                subtitle = if (detail != null) detail?.title.orEmpty()
                else "重新生成不会删掉旧计划,每一版都留在这里",
                onBack = { if (detail != null) detail = null else navController.popBackStack() }
            )

            val current = detail
            when {
                pendingDetailId != null -> LoadingBlock("正在打开这一版计划…")
                current != null -> DetailContent(
                    plan = current,
                    bottomPadding = padding.calculateBottomPadding()
                )
                listError != null -> ErrorBlock(
                    message = listError ?: "网络异常",
                    onRetry = { reloadKey++ }
                )
                plans == null -> LoadingBlock("正在读取历史计划…")
                plans!!.isEmpty() -> EmptyBlock()
                else -> HistoryList(
                    plans = plans!!,
                    onOpen = { pendingDetailId = it.id },
                    bottomPadding = padding.calculateBottomPadding()
                )
            }
        }

        // 详情打开失败:留在列表上给一句提示,别把用户扔在空白页
        detailError?.let { msg ->
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = colors.errorContainer,
                contentColor = colors.onErrorContainer,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(20.dp)
            ) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(msg, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f, fill = false))
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = { detailError = null }) { Text("知道了") }
                }
            }
        }
    }
}

/** 顶部返回栏:深层页隐藏了底部导航,返回必须由本页自己提供 */
@Composable
private fun HistoryTopBar(title: String, subtitle: String, onBack: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        IconButton(onClick = onBack) {
            Icon(AppIcons.ArrowBack, contentDescription = "返回")
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun LoadingBlock(text: String) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        CircularProgressIndicator()
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ErrorBlock(message: String, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(AppIcons.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp))
        Text("暂时打不开历史计划", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Button(onClick = onRetry, shape = RoundedCornerShape(12.dp)) { Text("重试") }
    }
}

@Composable
private fun EmptyBlock() {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            AppIcons.ClipboardList,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(36.dp)
        )
        Text("还没有历史计划", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            "生成过计划之后,每一版都会保留在这里,重新生成也不会覆盖旧的。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun HistoryList(
    plans: List<PlanSummaryDto>,
    onOpen: (PlanSummaryDto) -> Unit,
    bottomPadding: androidx.compose.ui.unit.Dp
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = bottomPadding + 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(plans, key = { it.id }) { plan ->
            SummaryCard(plan = plan, onClick = { onOpen(plan) })
        }
    }
}

@Composable
private fun SummaryCard(plan: PlanSummaryDto, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = colors.surface,
        tonalElevation = 1.dp,
        shadowElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    plan.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                StatusTag(plan.status)
            }
            Text(
                "第 ${plan.version} 版 · ${plan.startDate} ~ ${plan.examDate}",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "完成 ${plan.doneItems}/${plan.totalItems} · 共 ${plan.totalDays} 天",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                Text("查看", style = MaterialTheme.typography.labelLarge, color = colors.primary)
            }
        }
    }
}

@Composable
private fun StatusTag(status: String) {
    val colors = MaterialTheme.colorScheme
    val (label, container, content) = when (status) {
        "active" -> Triple("当前生效", colors.primaryContainer, colors.onPrimaryContainer)
        "archived" -> Triple("已归档", colors.surfaceVariant, colors.onSurfaceVariant)
        else -> Triple(status.ifBlank { "未知" }, colors.surfaceVariant, colors.onSurfaceVariant)
    }
    Surface(shape = RoundedCornerShape(999.dp), color = container, contentColor = content) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
        )
    }
}

/** 只读详情:阶段可展开看当日计划项,不做任何写操作——历史就是历史 */
@Composable
private fun DetailContent(plan: PlanDto, bottomPadding: androidx.compose.ui.unit.Dp) {
    val colors = MaterialTheme.colorScheme
    var expandedStageId by remember(plan.id) { mutableStateOf<Long?>(null) }
    val stages = plan.stages.sortedBy { it.sortOrder }
    val looseItems = plan.items.filter { item -> stages.none { it.id == item.stageId } }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = bottomPadding + 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "overview") {
            Surface(
                shape = MaterialTheme.shapes.large,
                color = colors.surface,
                tonalElevation = 1.dp,
                shadowElevation = 2.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            plan.title,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        StatusTag(plan.status)
                    }
                    Text(
                        "第 ${plan.version} 版 · 考期 ${plan.examDate}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant
                    )
                    Text(
                        "总进度 ${plan.progress.doneItems}/${plan.progress.totalItems} · 共 ${plan.progress.totalDays} 天 · ${plan.progress.totalMinutes} 分钟",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    LinearProgressIndicator(
                        progress = {
                            if (plan.progress.totalItems == 0) 0f
                            else plan.progress.doneItems.toFloat() / plan.progress.totalItems
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "这一版为只读回看,勾选请回到当前计划。",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant
                    )
                }
            }
        }

        stages.forEach { stage ->
            item(key = "stage-${stage.id}") {
                StageCard(
                    stage = stage,
                    plan = plan,
                    expanded = expandedStageId == stage.id,
                    onToggle = { expandedStageId = if (expandedStageId == stage.id) null else stage.id }
                )
            }
            if (expandedStageId == stage.id) {
                val stageItems = plan.items
                    .filter { it.stageId == stage.id }
                    .sortedWith(compareBy({ it.planDate }, { it.sortOrder }, { it.id }))
                items(stageItems, key = { "item-${it.id}" }) { item ->
                    HistoryItemRow(item)
                }
            }
        }

        if (looseItems.isNotEmpty()) {
            item(key = "loose-header") {
                Text("其他计划项", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
            items(
                looseItems.sortedWith(compareBy({ it.planDate }, { it.sortOrder }, { it.id })),
                key = { "loose-${it.id}" }
            ) { item ->
                HistoryItemRow(item)
            }
        }

        if (stages.isEmpty() && plan.items.isEmpty()) {
            item(key = "no-detail") {
                Text(
                    "这一版没有留下明细。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun StageCard(stage: PlanStageDto, plan: PlanDto, expanded: Boolean, onToggle: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val items = plan.items.filter { it.stageId == stage.id }
    val done = items.count { it.status == "done" }
    Surface(
        onClick = onToggle,
        shape = MaterialTheme.shapes.large,
        color = colors.surface,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stage.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "${stage.startDate} ~ ${stage.endDate}",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant
                )
                Text(
                    "完成 $done/${items.size}",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant
                )
            }
            Icon(
                if (expanded) AppIcons.ChevronUp else AppIcons.ChevronDown,
                contentDescription = if (expanded) "收起" else "展开",
                tint = colors.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun HistoryItemRow(item: PlanItemDto) {
    val colors = MaterialTheme.colorScheme
    val done = item.status == "done"
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (done) colors.primaryContainer.copy(alpha = 0.5f) else colors.surface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                AppIcons.CheckCircle,
                contentDescription = if (done) "已完成" else "未完成",
                tint = if (done) colors.primary else colors.onSurfaceVariant.copy(alpha = 0.35f),
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${item.planDate} · ${item.subject} · ${item.minutes} 分钟",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant
                )
            }
        }
    }
}

/** 把异常折成一句人话:HTTP 错误带状态码,其他用 message 兜底 */
private fun Throwable.shortMessage(prefix: String): String = when (this) {
    is retrofit2.HttpException -> "$prefix(HTTP ${code()})"
    else -> "$prefix(${message ?: "未知错误"})"
}
