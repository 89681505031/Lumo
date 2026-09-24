package app.lumo

import android.content.Context
import android.app.NotificationManager
import android.os.Build
import android.Manifest
import android.content.pm.PackageManager
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Session-scoped preference only. Firebase consent is never inherited when
 * users switch accounts on the same physical phone.
 */
internal object PushOptState {
    private const val PREF = "lumo_push"
    private fun fingerprint(session: String): String =
        MessageDigest.getInstance("SHA-256").digest(session.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }

    fun consented(context: Context, userId: String, session: String): Boolean {
        val p = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        return p.getBoolean("enabled", false) &&
            p.getString("user_id", "") == userId &&
            p.getString("session_hash", "") == fingerprint(session)
    }
    fun activeAccount(context: Context): Pair<String, String>? {
        val session = context.getSharedPreferences("lumo_session", Context.MODE_PRIVATE)
            .getString("token", null) ?: return null
        val p = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val userId = p.getString("user_id", "") ?: ""
        return if (userId.isNotBlank() && consented(context, userId, session))
            userId to session else null
    }
    @Synchronized
    fun enable(context: Context, userId: String, session: String) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString("user_id", userId)
            .putString("session_hash", fingerprint(session))
            .putBoolean("enabled", true)
            .putBoolean("pending_revoke", false)
            .commit()
    }
    fun revokePending(context: Context, userId: String, session: String): Boolean {
        val p = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        return p.getBoolean("pending_revoke", false) &&
            p.getString("user_id", "") == userId &&
            p.getString("session_hash", "") == fingerprint(session)
    }
    // Background work reads the *current* session from existing private prefs.
    // Never put the session token or FCM token into WorkManager's job database.
    fun pendingForCurrentSession(context: Context): Pair<String, String>? {
        val session = context.getSharedPreferences("lumo_session", Context.MODE_PRIVATE)
            .getString("token", null) ?: return null
        val userId = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString("user_id", "") ?: ""
        return if (userId.isNotBlank() && revokePending(context, userId, session))
            userId to session else null
    }
    fun dismissVisibleNotifications(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel("lumo_generic", 207)
    }
    // Make the local opt-out effective BEFORE attempting a network request.
    // A private pending flag lets the current session retry its server revoke.
    @Synchronized
    fun disableLocally(context: Context, userId: String, session: String) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString("user_id", userId)
            .putString("session_hash", fingerprint(session))
            .putBoolean("enabled", false)
            .putBoolean("pending_revoke", true)
            .commit()
        dismissVisibleNotifications(context)
    }
    @Synchronized
    fun clear(context: Context) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().clear().commit()
    }
    // Runtime permission and Android's app/channel switches are independent.
    // If an OS switch is turned off after opt-in, treat it as withdrawal of
    // consent rather than silently reactivating when permission returns.
    fun runtimePermissionGranted(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED

    fun permissionGranted(context: Context): Boolean {
        if (!runtimePermissionGranted(context)) return false
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!manager.areNotificationsEnabled()) return false
        val channel = manager.getNotificationChannel("lumo_messages")
        return channel == null || channel.importance != NotificationManager.IMPORTANCE_NONE
    }
}

/** Registration never sends a Firebase credential in a query string or log. */
internal object LumoPushApi {
    private fun send(session: String, method: String, pushToken: String? = null) {
        val req = Request.Builder()
            .url(BuildConfig.LUMO_HTTP_BASE + "/api/devices/push")
            .header("Authorization", "Bearer $session")
            .header("Cache-Control", "no-store")
        when (method) {
            "POST" -> req.post(JSONObject().put("platform", "android")
                .put("token", requireNotNull(pushToken)).toString()
                .toRequestBody("application/json".toMediaType()))
            "DELETE" -> req.delete()
            else -> error("Unsupported push operation")
        }
        Api.httpClient.newCall(req.build()).execute().use { response ->
            if (response.code == 401) throw SessionExpiredException()
            if (!response.isSuccessful)
                throw IllegalStateException("Не удалось обновить регистрацию (${response.code})")
        }
    }
    fun register(session: String, fcmToken: String) = send(session, "POST", fcmToken)
    fun revoke(session: String) = send(session, "DELETE")
}
