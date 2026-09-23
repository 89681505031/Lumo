package app.lumo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/** Debug variant only: every notification is generic and consent scoped. */
class LumoDebugFirebaseService : FirebaseMessagingService() {
    override fun onNewToken(fcmToken: String) {
        if (!BuildConfig.LUMO_FCM_CONFIGURED || !PushOptState.permissionGranted(this)) return
        val current = PushOptState.activeAccount(this) ?: return
        // This callback is allowed to run when the app is not open.
        Thread({
            val stillCurrent = PushOptState.activeAccount(this)
            if (stillCurrent != current || !PushOptState.permissionGranted(this)) return@Thread
            // No tokens, exception details, user IDs, or message data in logs.
            runCatching { LumoPushApi.register(current.second, fcmToken) }
        }, "lumo-push-token-refresh").start()
    }

    override fun onMessageReceived(message: RemoteMessage) {
        if (!BuildConfig.LUMO_FCM_CONFIGURED ||
            PushOptState.activeAccount(this) == null ||
            !PushOptState.permissionGranted(this)) return
        // Only the generic title/body is displayed even if an unexpected
        // upstream FCM payload contains sender metadata or message content.
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "lumo_messages"
        manager.createNotificationChannel(
            NotificationChannel(channelId, "Сообщения Lumo", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = Notification.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_lumo_icon)
            .setContentTitle("Lumo")
            .setContentText("Новое сообщение")
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        manager.notify(System.currentTimeMillis().toInt(), notification)
    }
}

internal object PushLifecycle {
    fun forgetOnLogout(context: Context) {
        // Clearing consent is synchronous; token deletion may complete later.
        PushOptState.clear(context)
        if (BuildConfig.LUMO_FCM_CONFIGURED) {
            runCatching {
                val messaging = FirebaseMessaging.getInstance()
                messaging.isAutoInitEnabled = false
                messaging.deleteToken()
            }
        }
    }
}
