package com.yanzhong.app.service

/**
 * 严格锁定的生效条件。
 *
 * 没有「显示在其他应用上层」时，服务无法覆盖桌面，也无法从后台把研钟拉回前台，
 * 严格模式"无法离开"的承诺就不成立。因此把权限检查收口到这里，UI 开钟与守护层共用同一判断。
 */
object StrictGuardPolicy {
    fun canStartStrict(overlayGranted: Boolean, pinConfigured: Boolean): Boolean =
        overlayGranted && pinConfigured

    fun needsOverlayPrompt(strict: Boolean, overlayGranted: Boolean): Boolean =
        strict && !overlayGranted
}
