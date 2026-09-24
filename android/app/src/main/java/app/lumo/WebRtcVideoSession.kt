package app.lumo

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import org.json.JSONObject
import org.webrtc.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Experimental one-to-one VIDEO + AUDIO transport.
 *
 * Construct only after the user explicitly starts video and Android has granted
 * CAMERA + RECORD_AUDIO. ICE is TURN relay-only, matching the reviewed audio
 * staging privacy model. No background capture is supported.
 */
class WebRtcVideoSession(
    context: Context,
    iceServers: List<LumoIceServer>,
    private val caller: Boolean,
    private val localRenderer: SurfaceViewRenderer,
    private val remoteRenderer: SurfaceViewRenderer,
    private val onLocalSignal: (String, JSONObject) -> Unit,
    private val onState: (String) -> Unit
) {
    private val app=context.applicationContext
    private val gate=Any()
    @Volatile private var closed=false
    private val pendingLocalIce=ArrayList<JSONObject>()
    private val pendingRemoteIce=ArrayList<IceCandidate>()
    private var localDescriptionPublished=false
    private var remoteDescriptionReady=false
    private val audioManager=app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val originalMode=audioManager.mode
    private val originalSpeaker=audioManager.isSpeakerphoneOn

    companion object {
        @Volatile private var initialized=false
        private fun ensureInitialized(context:Context){
            if(!initialized)synchronized(this){
                if(!initialized){
                    PeerConnectionFactory.initialize(
                        PeerConnectionFactory.InitializationOptions.builder(
                            context.applicationContext
                        ).createInitializationOptions()
                    )
                    initialized=true
                }
            }
        }
    }

    init {
        check(app.checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED){
            "Camera permission must be granted before creating WebRTC video"
        }
        check(app.checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED){
            "Microphone permission must be granted before creating WebRTC video"
        }
        require(iceServers.isNotEmpty()){"Private TURN server required"}
        require(iceServers.all{server->
            server.urls.isNotEmpty() &&
                server.urls.all{it.startsWith("turn:")||it.startsWith("turns:")} &&
                server.username.isNotBlank() && server.credential.isNotBlank()
        }){"Invalid TURN configuration"}
        ensureInitialized(app)
    }

    private val eglBase=EglBase.create()
    private val factory=PeerConnectionFactory.builder()
        .setVideoEncoderFactory(
            DefaultVideoEncoderFactory(eglBase.eglBaseContext,true,true)
        )
        .setVideoDecoderFactory(
            DefaultVideoDecoderFactory(eglBase.eglBaseContext)
        )
        .createPeerConnectionFactory()
    private val audioSource=factory.createAudioSource(MediaConstraints())
    private val audioTrack=factory.createAudioTrack("lumoVideoAudio",audioSource).apply{
        setEnabled(true)
    }
    private val videoSource=factory.createVideoSource(false)
    private val capturer:VideoCapturer=createCameraCapturer(app)
    private val textureHelper=SurfaceTextureHelper.create(
        "LumoVideoCapture",eglBase.eglBaseContext
    )
    private val localVideoTrack=factory.createVideoTrack("lumoVideo",videoSource).apply{
        setEnabled(true)
    }
    private var remoteVideoTrack:VideoTrack?=null
    @Volatile private var cameraCapturing=true

    private fun createCameraCapturer(context:Context):VideoCapturer{
        val enumerator:CameraEnumerator=if(Camera2Enumerator.isSupported(context))
            Camera2Enumerator(context) else Camera1Enumerator(true)
        val names=enumerator.deviceNames
        val preferred=names.firstOrNull{enumerator.isFrontFacing(it)}
            ?:names.firstOrNull{enumerator.isBackFacing(it)}
            ?:names.firstOrNull()
            ?:throw IllegalStateException("No camera available")
        return enumerator.createCapturer(preferred,null)
            ?:throw IllegalStateException("Could not open camera")
    }

    private val pc:PeerConnection=factory.createPeerConnection(
        PeerConnection.RTCConfiguration(iceServers.map{
            PeerConnection.IceServer.builder(it.urls)
                .setUsername(it.username)
                .setPassword(it.credential)
                .createIceServer()
        }).apply{
            iceTransportsType=PeerConnection.IceTransportsType.RELAY
            sdpSemantics=PeerConnection.SdpSemantics.UNIFIED_PLAN
        },
        object:PeerConnection.Observer{
            override fun onSignalingChange(newState:PeerConnection.SignalingState)=Unit
            override fun onIceConnectionChange(state:PeerConnection.IceConnectionState){
                if(closed)return
                when(state){
                    PeerConnection.IceConnectionState.CONNECTED,
                    PeerConnection.IceConnectionState.COMPLETED->
                        onState("Видеоканал подключён через TURN")
                    PeerConnection.IceConnectionState.CHECKING->
                        onState("Проверяем видеосоединение через TURN…")
                    PeerConnection.IceConnectionState.DISCONNECTED->
                        onState("Видеосвязь потеряна")
                    PeerConnection.IceConnectionState.FAILED->
                        onState("Видеосоединение не установлено")
                    PeerConnection.IceConnectionState.CLOSED->
                        onState("Видеосоединение завершено")
                    else->Unit
                }
            }
            override fun onIceConnectionReceivingChange(receiving:Boolean)=Unit
            override fun onIceGatheringChange(state:PeerConnection.IceGatheringState)=Unit
            override fun onIceCandidate(candidate:IceCandidate){
                if(closed)return
                val payload=JSONObject()
                    .put("candidate",candidate.sdp)
                    .put("sdpMid",candidate.sdpMid)
                    .put("sdpMLineIndex",candidate.sdpMLineIndex)
                val immediate=synchronized(gate){
                    if(!localDescriptionPublished){
                        pendingLocalIce.add(payload)
                        false
                    }else true
                }
                if(immediate&&!closed)onLocalSignal("ice",payload)
            }
            override fun onIceCandidatesRemoved(candidates:Array<IceCandidate>)=Unit
            override fun onAddStream(stream:MediaStream)=Unit
            override fun onRemoveStream(stream:MediaStream)=Unit
            override fun onDataChannel(channel:DataChannel)=Unit
            override fun onRenegotiationNeeded()=Unit
            override fun onAddTrack(receiver:RtpReceiver,streams:Array<MediaStream>){
                if(closed)return
                val track=receiver.track() as? VideoTrack ?:return
                remoteVideoTrack?.let{old->runCatching{old.removeSink(remoteRenderer)}}
                remoteVideoTrack=track
                track.addSink(remoteRenderer)
            }
        }
    )?:throw IllegalStateException("Could not initialize WebRTC peer connection")

    init {
        localRenderer.init(eglBase.eglBaseContext,null)
        remoteRenderer.init(eglBase.eglBaseContext,null)
        localRenderer.setMirror(true)
        remoteRenderer.setMirror(false)

        capturer.initialize(textureHelper,app,videoSource.capturerObserver)
        capturer.startCapture(640,480,24)
        localVideoTrack.addSink(localRenderer)

        pc.addTrack(audioTrack,listOf("lumoVideoAudioStream"))
        pc.addTrack(localVideoTrack,listOf("lumoVideoStream"))

        audioManager.mode=AudioManager.MODE_IN_COMMUNICATION
        audioManager.isSpeakerphoneOn=true
        onState("Камера включена. Создаём приватное видеосоединение…")
    }

    private fun checkActive(){check(!closed){"Video session has ended"}}

    private suspend fun createSdp(offer:Boolean):SessionDescription=
        suspendCancellableCoroutine{cont->
            val observer=object:SdpObserver{
                override fun onCreateSuccess(desc:SessionDescription){
                    if(cont.isActive)cont.resume(desc)
                }
                override fun onCreateFailure(error:String){
                    if(cont.isActive)cont.resumeWithException(
                        IllegalStateException("SDP creation failed")
                    )
                }
                override fun onSetSuccess()=Unit
                override fun onSetFailure(error:String)=Unit
            }
            if(offer)pc.createOffer(observer,MediaConstraints())
            else pc.createAnswer(observer,MediaConstraints())
        }

    private suspend fun setDescription(desc:SessionDescription,local:Boolean)=
        suspendCancellableCoroutine<Unit>{cont->
            val observer=object:SdpObserver{
                override fun onCreateSuccess(sdp:SessionDescription)=Unit
                override fun onCreateFailure(error:String)=Unit
                override fun onSetSuccess(){if(cont.isActive)cont.resume(Unit)}
                override fun onSetFailure(error:String){
                    if(cont.isActive)cont.resumeWithException(
                        IllegalStateException("SDP negotiation failed")
                    )
                }
            }
            if(local)pc.setLocalDescription(observer,desc)
            else pc.setRemoteDescription(observer,desc)
        }

    private fun publishDescription(type:String,description:SessionDescription){
        checkActive()
        onLocalSignal(type,JSONObject().put("sdp",description.description))
        val queued=synchronized(gate){
            localDescriptionPublished=true
            pendingLocalIce.toList().also{pendingLocalIce.clear()}
        }
        queued.forEach{payload->if(!closed)onLocalSignal("ice",payload)}
    }

    suspend fun start(){
        checkActive()
        if(caller){
            val offer=createSdp(true)
            checkActive()
            setDescription(offer,local=true)
            publishDescription("offer",offer)
        }else onState("Ожидаем предложение видеосоединения…")
    }

    private fun addRemoteCandidate(payload:JSONObject){
        if(closed)return
        val candidate=IceCandidate(
            payload.optString("sdpMid",""),
            payload.optInt("sdpMLineIndex",0),
            payload.getString("candidate")
        )
        val immediate=synchronized(gate){
            if(!remoteDescriptionReady){
                pendingRemoteIce.add(candidate)
                false
            }else true
        }
        if(immediate&&!closed)pc.addIceCandidate(candidate)
    }

    private fun markRemoteReady(){
        val buffered=synchronized(gate){
            remoteDescriptionReady=true
            pendingRemoteIce.toList().also{pendingRemoteIce.clear()}
        }
        buffered.forEach{if(!closed)pc.addIceCandidate(it)}
    }

    suspend fun apply(signal:LumoSignal){
        checkActive()
        when(signal.type){
            "ice"->addRemoteCandidate(signal.payload)
            "offer"->if(!caller&&!remoteDescriptionReady){
                setDescription(
                    SessionDescription(
                        SessionDescription.Type.OFFER,
                        signal.payload.getString("sdp")
                    ),
                    local=false
                )
                markRemoteReady()
                checkActive()
                val answer=createSdp(false)
                setDescription(answer,local=true)
                publishDescription("answer",answer)
            }
            "answer"->if(caller&&!remoteDescriptionReady){
                setDescription(
                    SessionDescription(
                        SessionDescription.Type.ANSWER,
                        signal.payload.getString("sdp")
                    ),
                    local=false
                )
                markRemoteReady()
            }
        }
    }

    fun setMuted(muted:Boolean){
        if(!closed)runCatching{audioTrack.setEnabled(!muted)}
    }

    fun setCameraEnabled(enabled:Boolean){
        if(closed)return
        if(enabled){
            if(!cameraCapturing){
                runCatching{
                    capturer.startCapture(640,480,24)
                    cameraCapturing=true
                    localVideoTrack.setEnabled(true)
                }
            }
        }else if(cameraCapturing){
            runCatching{localVideoTrack.setEnabled(false)}
            runCatching{capturer.stopCapture()}
            cameraCapturing=false
        }
    }

    fun setSpeakerphone(enabled:Boolean){
        if(!closed)runCatching{audioManager.isSpeakerphoneOn=enabled}
    }

    fun switchCamera(){
        if(closed)return
        (capturer as? CameraVideoCapturer)?.switchCamera(null)
    }

    fun stop(){
        synchronized(gate){
            if(closed)return
            closed=true
            pendingLocalIce.clear()
            pendingRemoteIce.clear()
        }
        runCatching{audioTrack.setEnabled(false)}
        runCatching{localVideoTrack.setEnabled(false)}
        if(cameraCapturing)runCatching{capturer.stopCapture()}
        cameraCapturing=false
        runCatching{remoteVideoTrack?.removeSink(remoteRenderer)}
        runCatching{localVideoTrack.removeSink(localRenderer)}
        runCatching{pc.close()}
        runCatching{pc.dispose()}
        runCatching{audioTrack.dispose()}
        runCatching{audioSource.dispose()}
        runCatching{localVideoTrack.dispose()}
        runCatching{videoSource.dispose()}
        runCatching{capturer.dispose()}
        runCatching{textureHelper.dispose()}
        runCatching{localRenderer.release()}
        runCatching{remoteRenderer.release()}
        runCatching{factory.dispose()}
        runCatching{eglBase.release()}
        runCatching{
            audioManager.isSpeakerphoneOn=originalSpeaker
            audioManager.mode=originalMode
        }
    }
}
