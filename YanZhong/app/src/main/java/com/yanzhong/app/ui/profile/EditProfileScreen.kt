package com.yanzhong.app.ui.profile

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.yanzhong.app.ui.theme.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EditProfileScreen(padding: PaddingValues, navController: NavHostController) {
    val vm: EditProfileViewModel = viewModel()
    val state by vm.ui.collectAsStateWithLifecycle()
    var showAvatarPicker by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) vm.uploadAvatar(uri)
    }
    LaunchedEffect(state.saved) { if (state.saved) navController.popBackStack() }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .widthIn(max = CONTENT_MAX_WIDTH)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .padding(bottom = padding.calculateBottomPadding() + 24.dp)
        ) {
            SubPageHeader(
                title = "编辑资料",
                meta = "头像、昵称与个性签名",
                onBack = { navController.popBackStack() },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)
            )
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                UserAvatar(state.selectedPreset, state.customAvatar, 96.dp)
                TextAction(text = "更换头像", onClick = { showAvatarPicker = true })

                OutlinedTextField(
                    value = state.nickname, onValueChange = vm::setNickname,
                    label = { Text("昵称") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    supportingText = { Text("${codePointLength(state.nickname)}/32") }
                )
                OutlinedTextField(
                    value = state.signature, onValueChange = vm::setSignature,
                    label = { Text("个性签名") }, minLines = 2, modifier = Modifier.fillMaxWidth(),
                    supportingText = { Text("${codePointLength(state.signature)}/100") }
                )

                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("性别", style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("secret" to "保密", "male" to "男", "female" to "女", "other" to "其他").forEach { (key, label) ->
                            FilterChip(selected = state.gender == key, onClick = { vm.setGender(key) }, label = { Text(label) })
                        }
                    }
                }

                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("生日", style = MaterialTheme.typography.bodyMedium)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                            .clickable { showDatePicker = true }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            state.birthday.ifBlank { "点击选择生日" },
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (state.birthday.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        if (state.birthday.isNotBlank()) {
                            TextAction(text = "清除", onClick = { vm.setBirthday("") })
                        }
                    }
                }

                if (state.error.isNotBlank()) {
                    Text(state.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth())
                }
                PrimaryButton(text = "保存", onClick = vm::save, loading = state.saving, enabled = !state.loading, modifier = Modifier.fillMaxWidth())
            }
        }
    }

    if (showAvatarPicker) {
        AlertDialog(
            onDismissRequest = { showAvatarPicker = false },
            confirmButton = {},
            title = { Text("选择头像") },
            text = {
                Column(
                    Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        PRESET_AVATARS.forEach { p ->
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(4.dp)) {
                                Box(Modifier.clickable { vm.pickPreset(p.key); showAvatarPicker = false }) {
                                    UserAvatar(p.key, null, 52.dp)
                                }
                                Text(p.label, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                    SecondaryButton(text = "从相册上传", onClick = { picker.launch("image/*"); showAvatarPicker = false }, modifier = Modifier.fillMaxWidth())
                    TextAction(text = "移除头像", onClick = { vm.clearAvatar(); showAvatarPicker = false })
                }
            }
        )
    }

    if (showDatePicker) {
        val initialMillis = remember(state.birthday) {
            runCatching { LocalDate.parse(state.birthday).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }.getOrNull()
        }
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = initialMillis,
            yearRange = 1900..LocalDate.now().year
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextAction(text = "确定", onClick = {
                    pickerState.selectedDateMillis?.let { millis ->
                        vm.setBirthday(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toString())
                    }
                    showDatePicker = false
                })
            },
            dismissButton = { TextAction(text = "取消", onClick = { showDatePicker = false }) }
        ) {
            DatePicker(state = pickerState)
        }
    }
}