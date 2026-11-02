package com.yanzhong.app.ui.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yanzhong.app.data.remote.AppUpdater
import com.yanzhong.app.data.remote.UpdateCenter
import com.yanzhong.app.data.remote.UpdatePolicy
import kotlinx.coroutines.launch

/** 挂在 Activity 根部的更新弹窗:登录页也能收到强更,前台恢复时按 6 小时节流复查。 */
@Composable
fun UpdateHost() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    val info by UpdateCenter.available.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        UpdateCenter.check(manual = false, context = context)
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                scope.launch { UpdateCenter.check(manual = false, context = context) }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val version = info ?: return
    var downloading by remember(version.versionCode) { mutableStateOf(false) }
    var progress by remember(version.versionCode) { mutableIntStateOf(0) }
    var error by remember(version.versionCode) { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!version.forced && !downloading) UpdateCenter.dismiss() },
        title = { Text("发现新版本 ${version.versionName}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                version.notes.forEach { note ->
                    Text("· $note", style = MaterialTheme.typography.bodyMedium)
                }
                Text(
                    "安装包 ${UpdatePolicy.formatFileSize(version.fileSize)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (downloading) {
                    LinearProgressIndicator(
                        progress = { progress / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "下载中 $progress%",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                if (!version.forced && !downloading) {
                    TextButton(onClick = { UpdateCenter.skip(context, version.versionCode) }) {
                        Text("跳过此版本")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !downloading,
                onClick = {
                    if (!AppUpdater.canInstall(context)) {
                        AppUpdater.openInstallPermissionSettings(context)
                        error = "请允许「研钟」安装未知应用后重试"
                        return@TextButton
                    }
                    error = null
                    downloading = true
                    scope.launch {
                        val result = AppUpdater.downloadAndInstall(context, version) { p -> progress = p }
                        downloading = false
                        result.onFailure { e -> error = e.message ?: "更新失败,请重试" }
                    }
                }
            ) { Text(if (downloading) "下载中…" else "立即更新") }
        },
        dismissButton = {
            if (!version.forced && !downloading) {
                TextButton(onClick = { UpdateCenter.dismiss() }) { Text("稍后") }
            }
        }
    )
}
