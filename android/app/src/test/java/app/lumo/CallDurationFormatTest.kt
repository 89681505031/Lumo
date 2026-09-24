package app.lumo

import org.junit.Assert.assertEquals
import org.junit.Test

class CallDurationFormatTest {
    @Test fun formatsShortCalls() {
        assertEquals("00:00",formatCallElapsed(0))
        assertEquals("00:09",formatCallElapsed(9))
        assertEquals("01:05",formatCallElapsed(65))
    }

    @Test fun formatsLongCallsAndClampsNegativeValues() {
        assertEquals("1:00:00",formatCallElapsed(3600))
        assertEquals("2:03:04",formatCallElapsed(7384))
        assertEquals("00:00",formatCallElapsed(-1))
    }
}
