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
        assertEquals(PushSyncAction.NONE, PushSyncPolicy.choose(false, true, false))
        assertEquals(PushSyncAction.NONE, PushSyncPolicy.choose(false, false, true))
        assertEquals(PushSyncAction.NONE, PushSyncPolicy.choose(false, false, false))
    }
}
