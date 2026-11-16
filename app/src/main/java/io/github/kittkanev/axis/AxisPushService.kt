package io.github.kittkanev.axis

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant

internal class AxisPushService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var task: Job? = null
    private val cloud get() = (application as AxisApplication).cloud
    private val notifications get() = getSystemService(NotificationManager::class.java)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!cloud.state.value.signedIn || !notifications.areNotificationsEnabled()) {
            stopSelf()
            return START_NOT_STICKY
        }
        notifications.createNotificationChannel(NotificationChannel("axis-push", getString(R.string.cloud_push_toggle), NotificationManager.IMPORTANCE_LOW))
        notifications.createNotificationChannel(NotificationChannel("axis-reminders", getString(R.string.cloud_reminders), NotificationManager.IMPORTANCE_DEFAULT))
        val service = notification("axis-push", getString(R.string.cloud_push_active)).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(SERVICE_ID, service, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(SERVICE_ID, service)
        active.value = true
        if (task?.isActive != true) task = scope.launch { listen() }
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private suspend fun listen() {
        val prefs = getSharedPreferences("axis-reminder-receipts", MODE_PRIVATE)
        val account = cloud.state.value.let { "${it.baseUrl}\n${it.email}" }
        val seen = LinkedHashSet(prefs.getString(account, "").orEmpty().split(',').filter(String::isNotEmpty))
        var backoff = 1_000L
        while (currentCoroutineContext().isActive && cloud.state.value.signedIn) {
            try {
                cloud.listen { event ->
                    val id = event.optString("id").takeIf { it.matches(Regex("[a-f0-9]{32}")) }
                    val text = event.optString("text").take(600)
                    if (id != null && text.isNotBlank() && seen.add(id)) {
                        while (seen.size > 256) seen.remove(seen.first())
                        prefs.edit().putString(account, seen.joinToString(",")).apply()
                        notifications.notify(id, EVENT_ID, notification("axis-reminders", text).setAutoCancel(true).build())
                    }
                }
                backoff = 1_000L
            } catch (error: CancellationException) {
                throw error
            } catch (error: AxisException) {
                if (error.httpStatus == 401) break
            } catch (_: Exception) {
                // Reconnect to the user's configured HTTPS backend.
            }
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(60_000L)
        }
        stopSelf()
    }

    private fun notification(channel: String, text: String) = Notification.Builder(this, channel)
        .setSmallIcon(R.drawable.ic_axis_tile).setContentTitle("Axis").setContentText(text)
        .setStyle(Notification.BigTextStyle().bigText(text)).setVisibility(Notification.VISIBILITY_PRIVATE)
        .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))

    override fun onDestroy() {
        scope.cancel()
        active.value = false
        super.onDestroy()
    }

    companion object {
        private const val SERVICE_ID = 8733
        private const val EVENT_ID = 8734
        private val active = MutableStateFlow(false)
        val running = active.asStateFlow()
    }
}
