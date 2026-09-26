package com.yanzhong.app.ui.legal

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yanzhong.app.data.remote.effectiveServerUrl
import kotlinx.coroutines.launch

/**
 * 用户协议 / 隐私政策在系统浏览器里打开,而不是内嵌一个 WebView。
 *
 * 理由很实际:协议是用户日后可能想翻出来看、想收藏、想转发给家人的东西。
 * WebView 里看一眼就关掉、"同意"两个字也就成了走过场;
 * 浏览器打开的页面跟着服务端一起版本化,改了协议用户看到的就是新的。
 *
 * 路径与服务端 app.ts 的静态路由一一对应,不要在别处另拼一遍字符串。
 */
const val LEGAL_PATH_TERMS = "terms"
const val LEGAL_PATH_PRIVACY = "privacy"

/** 协议全文的线上地址:换服务器(比如自建调试服)时链接跟着走,不会指向别人的站点 */
suspend fun legalUrl(path: String): String {
    val base = effectiveServerUrl().trim().trimEnd('/')
    return "$base/legal/$path"
}

/** 返回一个「打开协议页」的闭包;拿不到地址时退化为提示,不静默失败 */
@Composable
fun rememberPolicyOpener(): (String) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    return { path ->
        scope.launch { openLegal(context, path) }
    }
}

/** 直接打开协议页(给非 Compose 场景或已有点击回调的地方用) */
suspend fun openLegal(context: Context, path: String) {
    val url = legalUrl(path)
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }.onFailure {
        Toast.makeText(context, "没能打开浏览器。协议地址:$url", Toast.LENGTH_LONG).show()
    }
}

/**
 * 注册页的同意勾选。
 *
 * 语气上刻意不用「我已阅读并同意」那种法务腔:它只会训练用户闭眼打勾。
 * 这里说的是我们到底拿这些数据做什么,用户点开链接能自己去核对。
 */
@Composable
fun ConsentRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val opener = rememberPolicyOpener()
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.size(24.dp).padding(top = 2.dp)
        )
        Spacer(Modifier.size(6.dp))
        Text(
            text = "我知道这个账号只用来同步我的学习数据,并已看过",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(top = 3.dp)
        )
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 30.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        PolicyLink("用户协议") { opener(LEGAL_PATH_TERMS) }
        Text("和", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
        PolicyLink("隐私政策") { opener(LEGAL_PATH_PRIVACY) }
    }
}

/** 协议文字链:同一处只写一次样式,免得三处不一致 */
@Composable
fun PolicyLink(
    text: String,
    onClick: () -> Unit
) {
    TextButton(
        onClick = onClick,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 0.dp)
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

/** 一行协议链接(用于问卷提交按钮附近、账户页) */
@Composable
fun PolicyLinksRow(
    prefix: String,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val opener = rememberPolicyOpener()
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Text(
            prefix,
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant
        )
        PolicyLink("用户协议") { opener(LEGAL_PATH_TERMS) }
        Text("·", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        PolicyLink("隐私政策") { opener(LEGAL_PATH_PRIVACY) }
    }
}
