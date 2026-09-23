package app.lumo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LumoPushPayloadTest {
    @Test fun acceptsOnlyOurDataOnlyKind() {
        assertTrue(LumoPushPayload.accepts("lumo_message", false))
        assertFalse(LumoPushPayload.accepts(null, false))
        assertFalse(LumoPushPayload.accepts("unknown", false))
        assertFalse(LumoPushPayload.accepts("lumo_message", true))
        assertFalse(LumoPushPayload.accepts("unknown", true))
    }
}
