package com.yanzhong.app.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StrictGuardPolicyTest {
    @Test
    fun strictGuardRequiresOverlayPermission() {
        assertTrue(StrictGuardPolicy.canStartStrict(overlayGranted = true, pinConfigured = true))
        assertFalse(StrictGuardPolicy.canStartStrict(overlayGranted = false, pinConfigured = true))
    }

    @Test
    fun strictGuardRequiresEmergencyPin() {
        assertFalse(StrictGuardPolicy.canStartStrict(overlayGranted = true, pinConfigured = false))
    }

    @Test
    fun strictModeWithoutPermissionNeedsPrompt() {
        assertTrue(StrictGuardPolicy.needsOverlayPrompt(strict = true, overlayGranted = false))
        assertFalse(StrictGuardPolicy.needsOverlayPrompt(strict = true, overlayGranted = true))
        assertFalse(StrictGuardPolicy.needsOverlayPrompt(strict = false, overlayGranted = false))
    }
}
