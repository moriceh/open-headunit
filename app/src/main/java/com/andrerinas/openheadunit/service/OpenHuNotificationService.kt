package com.andrerinas.openheadunit.service

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.andrerinas.openheadunit.aap.AapService
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.ZlinkHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

class OpenHuNotificationService : NotificationListenerService() {

    private val serviceScope = CoroutineScope(Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        AppLog.i("OpenHuNotificationService: onCreate() called by system")
        ensureAapServiceRunning()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        AppLog.i("OpenHuNotificationService: onListenerConnected() - System bound service successfully")
        ensureAapServiceRunning()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureAapServiceRunning()
        return START_STICKY
    }

    private fun ensureAapServiceRunning() {
        try {
            AppLog.i("OpenHuNotificationService: Ensuring AapService is running...")
            val intent = Intent(this, AapService::class.java)
            ContextCompat.startForegroundService(this, intent)
            
            // Trigger Zlink auto-kill watchdog if enabled
            ZlinkHelper.startBootWatchdog(this, serviceScope)
        } catch (e: Exception) {
            AppLog.e("OpenHuNotificationService: Failed to start AapService: ${e.message}", e)
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {}
    override fun onNotificationRemoved(sbn: StatusBarNotification?) {}

    companion object {
        fun isEnabled(context: Context): Boolean {
            val enabledListeners = NotificationManagerCompat.getEnabledListenerPackages(context)
            if (enabledListeners.contains(context.packageName)) return true
            
            val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
            return flat != null && flat.contains(context.packageName)
        }

        fun openNotificationSettings(context: Context) {
            try {
                val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                AppLog.e("OpenHuNotificationService: Failed to open notification listener settings: ${e.message}", e)
            }
        }
    }
}
