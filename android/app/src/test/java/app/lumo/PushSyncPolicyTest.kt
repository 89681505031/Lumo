package app.lumo

import org.junit.Assert.assertEquals
import org.junit.Test

class PushSyncPolicyTest {
    @Test fun revocationAlwaysTakesPriority() {
        for (consented in listOf(true, false)) {
            for (granted in listOf(true, false)) {
                assertEquals(PushSyncAction.REVOKE, PushSyncPolicy.choose(true, consented, granted))
            }
        }
    }

    @Test fun tokenRegistrationRequiresBothOptInAndOsPermission() {
        assertEquals(PushSyncAction.REGISTER, PushSyncPolicy.choose(false, true, true))
        assertEquals(PushSyncAction.REVOKE, PushSyncPolicy.choose(false, true, false))
        assertEquals(PushSyncAction.NONE, PushSyncPolicy.choose(false, false, true))
        assertEquals(PushSyncAction.NONE, PushSyncPolicy.choose(false, false, false))
    }

    @Test fun revokingOsPermissionDoesNotSilentlyReenableAfterItReturns() {
        assertEquals(PushSyncAction.REVOKE, PushSyncPolicy.choose(false, true, false))
        // The caller stores a pending server revoke and clears local consent.
        assertEquals(PushSyncAction.REVOKE, PushSyncPolicy.choose(true, false, true))
        assertEquals(PushSyncAction.NONE, PushSyncPolicy.choose(false, false, true))
    }
}
