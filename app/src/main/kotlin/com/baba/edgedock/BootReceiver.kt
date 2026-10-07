package com.baba.edgedock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.baba.edgedock.data.UserPreferences
import com.baba.edgedock.overlay.OverlayService

/**
 * Restarts the overlay after reboot when the user left the dock enabled and
 * still has overlay permission.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!Settings.canDrawOverlays(context)) return

        val preferences = UserPreferences(context.applicationContext)
        if (!preferences.isDockEnabledBlocking()) return

        OverlayService.start(context.applicationContext)
    }
}
