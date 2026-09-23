package app.lumo

/**
 * The FCM provider must never display a notification automatically in the
 * background: all notifications go through consent/permission checks here.
 */
internal object LumoPushPayload {
    fun accepts(kind: String?, hasNotificationPayload: Boolean): Boolean =
        kind == "lumo_message" && !hasNotificationPayload
}
