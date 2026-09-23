package app.lumo

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AudioStartGateTest {
    @Test fun canceledTurnResponseCannotStartMicrophone() {
        val gate = AudioStartGate()
        val first = gate.begin()!!
        assertTrue(gate.isCurrent(first))
        gate.invalidate() // User hangs up while HTTP fetch is still in flight.
        assertFalse(gate.isCurrent(first))
        val second = gate.begin()!!
        assertFalse(gate.isCurrent(first))
        assertTrue(gate.isCurrent(second))
    }

    @Test fun navigatingAwayPermanentlyPreventsRestarts() {
        val gate = AudioStartGate()
        val request = gate.begin()!!
        gate.dispose()
        assertTrue(gate.isDisposed())
        assertFalse(gate.isCurrent(request))
        assertNull(gate.begin())
    }

    @Test fun crossThreadCancellationInvalidatesPendingStartup() {
        val gate = AudioStartGate()
        val ready = CountDownLatch(1)
        val released = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val ticket = gate.begin()!!
            val observed = executor.submit<Boolean> {
                ready.countDown()
                released.await(3, TimeUnit.SECONDS)
                gate.isCurrent(ticket)
            }
            assertTrue(ready.await(3, TimeUnit.SECONDS))
            gate.dispose()
            released.countDown()
            assertFalse(observed.get(3, TimeUnit.SECONDS))
        } finally {
            released.countDown()
            executor.shutdownNow()
        }
    }
}
