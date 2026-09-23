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
        if (!BuildConfig.LUMO_FCM_CONFIGURED) return
        PushLifecycle.onAppResume(this)
        val current = PushOptState.activeAccount(this) ?: return
        if (!PushOptState.permissionGranted(this)) return
        // Do not launch an untracked Thread: Android may terminate the
        // background FCM service immediately after this callback returns.
        // WorkManager re-fetches the latest token for the CURRENT session.
        LumoPushSyncWorker.schedule(this)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        if (!BuildConfig.LUMO_FCM_CONFIGURED) return
        // FCM may wake us while app settings have changed in the background.
        // Withdrawal starts an authenticated remote revoke, not just filtering.
        PushLifecycle.onAppResume(this)
        if (!LumoPushPayload.accepts(message.data["kind"], message.notification != null) ||
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
        // Permission or account can change while constructing the Android UI.
        if (PushOptState.activeAccount(this) == null ||
            !PushOptState.permissionGranted(this)) return
        manager.notify("lumo_generic", 207, notification)
    }
}

internal object PushLifecycle {
    fun onAppStart(context: Context) {
        onAppResume(context)
        // The local-only offline disable survives app restarts and is retried
        // automatically when Android reports network connectivity.
        LumoPushSyncWorker.schedule(context)
    }

    fun onAppResume(context: Context) {
        if (!BuildConfig.LUMO_FCM_CONFIGURED) return
        val account = PushOptState.activeAccount(context) ?: return
        if (PushOptState.permissionGranted(context)) return
        // OS notifications or this notification channel were explicitly
        // disabled. Revoke local consent before networking; never auto opt-in
        // again just because the OS switch is later re-enabled.
        PushOptState.disableLocally(context, account.first, account.second)
        FirebaseMessaging.getInstance().isAutoInitEnabled = false
        LumoPushSyncWorker.schedule(context)
    }

    fun forgetOnLogout(context: Context) {
        PushOptState.dismissVisibleNotifications(context)
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
