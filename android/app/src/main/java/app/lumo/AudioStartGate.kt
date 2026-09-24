package app.lumo

/**
 * Main-thread UI and asynchronous TURN/WebRTC startup share this gate.
 * A canceled request must never open the microphone after navigation, hangup
 * or a newer attempt. This class has no Android dependencies so it is unit-tested.
 */
internal class AudioStartGate {
    private var generation = 0L
    private var disposed = false

    @Synchronized
    fun begin(): Long? = if (disposed) null else ++generation

    @Synchronized
    fun isCurrent(ticket: Long): Boolean = !disposed && generation == ticket

    @Synchronized
    fun isDisposed(): Boolean = disposed

    @Synchronized
    fun invalidate() { generation++ }

    @Synchronized
    fun dispose() {
        disposed = true
        generation++
    }
}
