package com.yanzhong.app.ui.profile

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yanzhong.app.data.remote.ApiClient
import com.yanzhong.app.data.remote.TokenStore
import com.yanzhong.app.data.remote.UpdateIdentityReq
import com.yanzhong.app.data.remote.apiErrorMessage
import com.yanzhong.app.data.remote.refreshIdentity
import com.yanzhong.app.data.util.compressImageToBase64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

data class EditProfileUiState(
    val nickname: String = "", val signature: String = "", val gender: String? = null,
    val birthday: String = "", val selectedPreset: String? = null, val customAvatar: String? = null,
    val loading: Boolean = true, val saving: Boolean = false, val error: String = "", val saved: Boolean = false
)

class EditProfileViewModel(app: Application) : AndroidViewModel(app) {
    private val _ui = MutableStateFlow(EditProfileUiState())
    val ui: StateFlow<EditProfileUiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            val cached = TokenStore.currentIdentity()
            _ui.update { it.copy(nickname = cached.nickname.orEmpty(), signature = cached.signature.orEmpty(), gender = cached.gender, birthday = cached.birthday.orEmpty(), selectedPreset = cached.avatarPreset, customAvatar = cached.avatar, loading = true) }
            val fresh = refreshIdentity()
            if (fresh != null) {
                _ui.update { it.copy(nickname = fresh.nickname.orEmpty(), signature = fresh.signature.orEmpty(), gender = fresh.gender, birthday = fresh.birthday.orEmpty(), selectedPreset = fresh.avatarPreset, customAvatar = fresh.avatar, loading = false) }
            } else {
                _ui.update { it.copy(loading = false) }
            }
        }
    }

    fun setNickname(v: String) { _ui.update { it.copy(nickname = v, error = "") } }
    fun setSignature(v: String) { _ui.update { it.copy(signature = v, error = "") } }
    fun setGender(v: String?) { _ui.update { it.copy(gender = v, error = "") } }
    fun setBirthday(v: String) { _ui.update { it.copy(birthday = v, error = "") } }
    fun pickPreset(key: String) { _ui.update { it.copy(selectedPreset = key, customAvatar = null, error = "") } }
    fun clearAvatar() { _ui.update { it.copy(selectedPreset = null, customAvatar = null, error = "") } }

    fun uploadAvatar(uri: Uri) {
        val base64 = compressImageToBase64(getApplication(), uri, 512, 85)
        if (base64 == null) { _ui.update { it.copy(error = "这张图读不出来,换一张试试") } }
        else _ui.update { it.copy(customAvatar = base64, selectedPreset = null, error = "") }
    }

    fun save() {
        val s = _ui.value
        val today = LocalDate.now().toString()
        val err = validateNickname(s.nickname) ?: validateSignature(s.signature)
            ?: validateGender(s.gender) ?: validateBirthday(s.birthday, today)
            ?: avatarConflict(s.selectedPreset, s.customAvatar)
        if (err != null) { _ui.update { it.copy(error = err) }; return }
        viewModelScope.launch {
            _ui.update { it.copy(saving = true, error = "") }
            val result = runCatching {
                ApiClient.api().updateAccountMe(
                    UpdateIdentityReq(
                        nickname = s.nickname.trim().ifBlank { null },
                        signature = s.signature.trim().ifBlank { null },
                        gender = s.gender,
                        birthday = s.birthday.ifBlank { null },
                        avatarPreset = s.selectedPreset,
                        avatar = s.customAvatar
                    )
                )
            }
            result.fold({ resp ->
                val id = resp.identity
                TokenStore.saveIdentity(TokenStore.IdentityCache(id.nickname, id.signature, id.gender, id.birthday, id.avatarPreset, id.avatar))
                _ui.update { it.copy(saving = false, saved = true) }
            }, { e ->
                _ui.update { it.copy(saving = false, error = "保存失败:${e.apiErrorMessage()}") }
            })
        }
    }
}