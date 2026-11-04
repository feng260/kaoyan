package com.yanzhong.app.ui.plan

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yanzhong.app.data.remote.AdjustmentOptionDto

/** 调整对话的常用一句话:不知道怎么说时点一下就能发 */
internal val QUICK_ADJUSTMENTS = listOf(
    "今天临时有事",
    "这周时间变少",
    "想多练数学",
    "任务太多，减一点",
    "帮我重新排一下"
)

/** 方案代号:A/B/C/D,按服务端下发顺序 */
internal fun optionLetter(index: Int): String =
    ('A'.code + index.coerceIn(0, 25)).toChar().toString()

/** 方案卡标题:带「方案 X」前缀,考生对比时不用记整串 label */
internal fun optionHeadline(option: AdjustmentOptionDto, index: Int = 0): String {
    val label = option.label.ifBlank { "调整方案" }
    return "方案 ${optionLetter(index)} · $label"
}

/** 一眼比较方案的影响指标,没有可量化变化时返回空串 */
internal fun impactSummary(option: AdjustmentOptionDto): String =
    optionImpactLines(option).joinToString(" · ")

/**
 * 一条可比较的调整方案:标题 + 说明 + 量化影响。
 * 选中只改变本地预览,真正生效仍要点「按这个方案调整」并再确认一次。
 */
@Composable
internal fun AdjustmentOptionCard(
    option: AdjustmentOptionDto,
    index: Int,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
        else MaterialTheme.colorScheme.surface,
        border = if (selected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick)
    ) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            RadioButton(selected = selected, onClick = onClick, enabled = enabled)
            Spacer(Modifier.width(4.dp))
            Column(Modifier.weight(1f).padding(top = 6.dp, bottom = 4.dp)) {
                Text(optionHeadline(option, index), style = MaterialTheme.typography.titleSmall)
                if (option.summary.isNotBlank()) {
                    Text(
                        option.summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                val impact = impactSummary(option)
                if (impact.isNotBlank()) {
                    Text(
                        impact,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }
        }
    }
}
