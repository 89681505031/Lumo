package app.lumo

/** Pure consent decision table used by the background reconciliation worker. */
internal enum class PushSyncAction { NONE, REGISTER, REVOKE }

internal object PushSyncPolicy {
    fun choose(pendingRevoke: Boolean, consented: Boolean, osPermissionGranted: Boolean): PushSyncAction =
        when {
            pendingRevoke -> PushSyncAction.REVOKE
            consented && osPermissionGranted -> PushSyncAction.REGISTER
            else -> PushSyncAction.NONE
        }
}
