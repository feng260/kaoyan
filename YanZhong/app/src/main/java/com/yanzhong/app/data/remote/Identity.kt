package com.yanzhong.app.data.remote

/** 拉服务端身份并写本地缓存;失败静默返回 null,调用方回落已有缓存。 */
suspend fun refreshIdentity(): TokenStore.IdentityCache? = runCatching {
    val id = ApiClient.api().getAccountMe().identity
    val cache = TokenStore.IdentityCache(
        nickname = id.nickname, signature = id.signature, gender = id.gender,
        birthday = id.birthday, avatarPreset = id.avatarPreset, avatar = id.avatar
    )
    TokenStore.saveIdentity(cache)
    cache
}.getOrNull()