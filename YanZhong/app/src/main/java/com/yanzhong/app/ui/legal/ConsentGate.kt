package com.yanzhong.app.ui.legal

import android.app.Activity
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yanzhong.app.ui.theme.AppCard
import com.yanzhong.app.ui.theme.AppIcons
import com.yanzhong.app.ui.theme.BrandGradient
import com.yanzhong.app.ui.theme.PrimaryButton

/** 首启同意门:同意前不启动任何业务网络请求、不申请通知权限 */
@Composable
fun ConsentGate() {
    val vm: ConsentViewModel = viewModel()
    val context = LocalContext.current
    val opener = rememberPolicyOpener()
    var checked by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(BrandGradient))
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(Modifier.fillMaxWidth().widthIn(max = 440.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        AppIcons.GraduationCap,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(40.dp)
                    )
                    Spacer(Modifier.size(12.dp))
                    Text("研钟", style = MaterialTheme.typography.headlineSmall, color = Color.White)
                }
                Spacer(Modifier.height(20.dp))
                AppCard(contentPadding = PaddingValues(20.dp)) {
                    Text("开始之前", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "研钟只收集让多设备同步学习记录所必需的数据。请先了解以下处理方式:",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "· 用户名、邮箱、学习记录保存在研钟服务器,密码只存哈希\n" +
                            "· 生成计划时会把备考档案发送给模型服务商 DeepSeek\n" +
                            "· 注册与找回密码的验证码邮件经 SMTP 服务商发送\n" +
                            "· 数据库每日备份加密存放在腾讯云 COS,保留 30 天",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = checked, onCheckedChange = { checked = it })
                        Text(
                            "我已阅读并同意",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 4.dp)
                    ) {
                        PolicyLink("用户协议") { opener(LEGAL_PATH_TERMS) }
                        Text("和", style = MaterialTheme.typography.bodyMedium)
                        PolicyLink("隐私政策") { opener(LEGAL_PATH_PRIVACY) }
                    }
                    Spacer(Modifier.height(16.dp))
                    PrimaryButton(
                        text = "同意并继续",
                        onClick = {
                            if (busy) return@PrimaryButton
                            busy = true
                            vm.accept { busy = false }
                        },
                        enabled = checked && !busy,
                        loading = busy
                    )
                    TextButton(
                        onClick = { (context as? Activity)?.finish() },
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    ) { Text("不同意，退出应用") }
                }
            }
        }
    }
}
