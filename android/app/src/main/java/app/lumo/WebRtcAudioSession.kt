package app.lumo

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import org.json.JSONObject
import org.webrtc.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.UUID

/**
 * Experimental one-to-one AUDIO ONLY transport.
 *
 * Construct only after explicit user action and a runtime RECORD_AUDIO grant.
 * No default public STUN service: require server-provisioned TURN relays and
 * RELAY-only ICE so peer IP addresses are not intentionally exposed via ICE.
 * Do not use in production before real-device testing and security review.
 */
class WebRtcAudioSession(
    context: Context,
    iceServers: List<LumoIceServer>,
    private val caller: Boolean,
    private val onLocalSignal: (String, JSONObject) -> Unit,
    private val onState: (String) -> Unit,
    private val onConnectionState: (Boolean) -> Unit
) {
    private val app = context.applicationContext
    private val gate = Any()
    @Volatile private var closed = false
    private val pendingLocalIce = ArrayList<JSONObject>()
    private val pendingRemoteIce = ArrayList<IceCandidate>()
    private var localDescriptionPublished = false
    private var remoteDescriptionReady = false
    private var activeMediaSessionId: String? = if (caller) UUID.randomUUID().toString() else null
    private val audioRouter = CallAudioRouter(app, preferSpeaker = false)

    companion object {
        @Volatile private var initialized = false
        private fun ensureInitialized(context: Context) {
            if (!initialized) synchronized(this) {
                if (!initialized) {
                    PeerConnectionFactory.initialize(
                        PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                            .createInitializationOptions()
                    )
                    initialized = true
                }
            }
        }
    }

    init {
        check(app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            "The microphone permission must be granted before creating WebRTC audio"
        }
        require(iceServers.isNotEmpty()) { "Private TURN server required" }
        require(iceServers.all { server ->
            server.urls.isNotEmpty() && server.urls.all { it.startsWith("turn:") || it.startsWith("turns:") } &&
                server.username.isNotBlank() && server.credential.isNotBlank()
        }) { "Invalid TURN configuration" }
        ensureInitialized(app)
    }

    private val factory: PeerConnectionFactory = PeerConnectionFactory.builder().createPeerConnectionFactory()
    private val audioSource: AudioSource = factory.createAudioSource(MediaConstraints())
    private val audioTrack: AudioTrack = factory.createAudioTrack("lumoAudio", audioSource).apply {
        setEnabled(true)
    }
    private val pc: PeerConnection = factory.createPeerConnection(
        PeerConnection.RTCConfiguration(iceServers.map {
            PeerConnection.IceServer.builder(it.urls)
                .setUsername(it.username)
                .setPassword(it.credential)
                .createIceServer()
        }).apply {
            iceTransportsType = PeerConnection.IceTransportsType.RELAY
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        },
        object : PeerConnection.Observer {
            override fun onSignalingChange(newState: PeerConnection.SignalingState) = Unit
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                if (closed) return
                when (state) {
                    PeerConnection.IceConnectionState.CONNECTED,
                    PeerConnection.IceConnectionState.COMPLETED -> {
                        onConnectionState(true)
                        onState("Аудиоканал подключён через TURN")
                    }
                    PeerConnection.IceConnectionState.CHECKING -> {
                        onConnectionState(false)
                        onState("Проверяем соединение через TURN…")
                    }
                    PeerConnection.IceConnectionState.DISCONNECTED -> {
                        onConnectionState(false)
                        onState("Связь потеряна. Пытаемся восстановить…")
                    }
                    PeerConnection.IceConnectionState.FAILED -> {
                        onConnectionState(false)
                        onState("Соединение не установлено")
                    }
                    PeerConnection.IceConnectionState.CLOSED -> {
                        onConnectionState(false)
                        onState("Соединение завершено")
                    }
                    else -> Unit
                }
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
            override fun onIceCandidate(candidate: IceCandidate) {
                if (closed) return
                val sessionId = synchronized(gate) { activeMediaSessionId } ?: return
                val payload = JSONObject()
                    .put("mediaSessionId", sessionId)
                    .put("candidate", candidate.sdp)
                    .put("sdpMid", candidate.sdpMid)
                    .put("sdpMLineIndex", candidate.sdpMLineIndex)
                val immediate = synchronized(gate) {
                    if (!localDescriptionPublished) {
                        pendingLocalIce.add(payload)
                        false
                    } else true
                }
                if (immediate && !closed) onLocalSignal("ice", payload)
            }
            override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) = Unit
            override fun onAddStream(stream: MediaStream) = Unit
            override fun onRemoveStream(stream: MediaStream) = Unit
            override fun onDataChannel(channel: DataChannel) = Unit
            override fun onRenegotiationNeeded() = Unit
            override fun onAddTrack(receiver: RtpReceiver, streams: Array<MediaStream>) = Unit
        }
    ) ?: throw IllegalStateException("Could not initialize WebRTC peer connection")

    init {
        pc.addTrack(audioTrack, listOf("lumoAudioStream"))
        audioRouter.start()
        onState("WebRTC готов. Обмен ключами соединения…")
    }

    private fun checkActive() {
        check(!closed) { "Audio session has ended" }
    }

    private suspend fun createSdp(offer: Boolean): SessionDescription = suspendCancellableCoroutine { cont ->
        val observer = object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription) {
                if (cont.isActive) cont.resume(desc)
            }
            override fun onCreateFailure(error: String) {
                if (cont.isActive) cont.resumeWithException(IllegalStateException("SDP creation failed"))
            }
            override fun onSetSuccess() = Unit
            override fun onSetFailure(error: String) = Unit
        }
        if (offer) pc.createOffer(observer, MediaConstraints())
        else pc.createAnswer(observer, MediaConstraints())
    }

    private suspend fun setDescription(desc: SessionDescription, local: Boolean) =
        suspendCancellableCoroutine<Unit> { cont ->
            val observer = object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription) = Unit
                override fun onCreateFailure(error: String) = Unit
                override fun onSetSuccess() {
                    if (cont.isActive) cont.resume(Unit)
                }
                override fun onSetFailure(error: String) {
                    if (cont.isActive) cont.resumeWithException(
                        IllegalStateException("SDP negotiation failed")
                    )
                }
            }
            if (local) pc.setLocalDescription(observer, desc)
            else pc.setRemoteDescription(observer, desc)
        }

    private fun publishDescription(type: String, description: SessionDescription) {
        checkActive()
        val sessionId = synchronized(gate) { activeMediaSessionId }
            ?: throw IllegalStateException("Media session is not initialized")
        onLocalSignal(
            type,
            JSONObject().put("mediaSessionId", sessionId)
                .put("sdp", description.description)
        )
        val queued = synchronized(gate) {
            localDescriptionPublished = true
            pendingLocalIce.toList().also { pendingLocalIce.clear() }
        }
        queued.forEach { payload ->
            if (!closed) onLocalSignal("ice", payload)
        }
    }

    suspend fun start() {
        checkActive()
        if (caller) {
            val offer = createSdp(true)
            checkActive()
            setDescription(offer, local = true)
            publishDescription("offer", offer)
        } else {
            onState("Ожидаем предложение аудиосоединения…")
        }
    }

    private fun addRemoteCandidate(payload: JSONObject) {
        if (closed) return
        val candidate = IceCandidate(
            payload.optString("sdpMid", ""),
            payload.optInt("sdpMLineIndex", 0),
            payload.getString("candidate")
        )
        val immediate = synchronized(gate) {
            if (!remoteDescriptionReady) {
                pendingRemoteIce.add(candidate)
                false
            } else true
        }
        if (immediate && !closed) pc.addIceCandidate(candidate)
    }

    private fun remoteDescriptionReady() {
        val buffered = synchronized(gate) {
            remoteDescriptionReady = true
            pendingRemoteIce.toList().also { pendingRemoteIce.clear() }
        }
        buffered.forEach { if (!closed) pc.addIceCandidate(it) }
    }

    /** Incoming signals must be applied sequentially, in server sequence order. */
    fun mediaSessionId(): String? = synchronized(gate) { activeMediaSessionId }

    private fun selectRemoteSession(sessionId: String): Boolean = synchronized(gate) {
        if (closed || caller) return@synchronized activeMediaSessionId == sessionId
        if (activeMediaSessionId != sessionId) {
            activeMediaSessionId = sessionId
            localDescriptionPublished = false
            remoteDescriptionReady = false
            pendingLocalIce.clear()
            pendingRemoteIce.clear()
        }
        true
    }

    private fun matchesSession(sessionId: String): Boolean = synchronized(gate) {
        !closed && activeMediaSessionId == sessionId
    }

    /** Incoming signals must be applied sequentially, in server sequence order. */
    suspend fun apply(signal: LumoSignal) {
        checkActive()
        val sessionId = signal.payload.optString("mediaSessionId", "")
        if (sessionId.isBlank()) return
        when (signal.type) {
            "ice" -> if (matchesSession(sessionId)) addRemoteCandidate(signal.payload)
            "offer" -> if (!caller && selectRemoteSession(sessionId)) {
                val negotiate = synchronized(gate) {
                    activeMediaSessionId == sessionId && !remoteDescriptionReady
                }
                if (negotiate) {
                    setDescription(
                        SessionDescription(SessionDescription.Type.OFFER, signal.payload.getString("sdp")),
                        local = false
                    )
                    remoteDescriptionReady()
                    checkActive()
                    val answer = createSdp(false)
                    setDescription(answer, local = true)
                    publishDescription("answer", answer)
                }
            }
            "answer" -> if (caller && matchesSession(sessionId) && !remoteDescriptionReady) {
                setDescription(
                    SessionDescription(SessionDescription.Type.ANSWER, signal.payload.getString("sdp")),
                    local = false
                )
                remoteDescriptionReady()
            }
        }
    }

    fun setMuted(muted:Boolean) {
        if (closed) return
        runCatching { audioTrack.setEnabled(!muted) }
    }

    fun availableAudioRoutes():List<CallAudioRoute> =
        if (closed) emptyList() else audioRouter.availableRoutes()

    fun selectedAudioRouteId():String? =
        if (closed) null else audioRouter.selectedRouteId()

    fun selectAudioRoute(routeId:String):Boolean =
        !closed && audioRouter.select(routeId)

    fun stop() {
        synchronized(gate) {
            if (closed) return
            closed = true
            pendingLocalIce.clear()
            pendingRemoteIce.clear()
        }
        onConnectionState(false)
        // Disable microphone capture before tearing down native resources.
        runCatching { audioTrack.setEnabled(false) }
        runCatching { pc.close() }
        runCatching { pc.dispose() }
        runCatching { audioTrack.dispose() }
        runCatching { audioSource.dispose() }
        runCatching { factory.dispose() }
        audioRouter.stop()
    }
}
