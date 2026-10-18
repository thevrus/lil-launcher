package dev.vr.tvlaunch

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent

/**
 * Apps cannot sleep the device directly, and TV boxes usually lack the device-admin feature lockNow() needs. The lock-screen
 * global action is the one path left. The service is only switched on for that moment: while any accessibility service
 * runs, the system wakes the screensaver straight away.
 */
class SleepService : AccessibilityService() {
    override fun onServiceConnected() {
        performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
        disableSelf()
    }
    override fun onAccessibilityEvent(e: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    companion object {
        /** Needs WRITE_SECURE_SETTINGS, granted once over adb; false without it. */
        fun sleep(c: Context) = try {
            val r = c.contentResolver
            val me = "${c.packageName}/${SleepService::class.java.name}"
            val on = Settings.Secure.getString(r, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)?.split(':')?.filter { it.isNotEmpty() }.orEmpty()
            Settings.Secure.putString(r, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, (on - me + me).joinToString(":"))
            Settings.Secure.putInt(r, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            true
        } catch (_: SecurityException) { false }
    }
}
