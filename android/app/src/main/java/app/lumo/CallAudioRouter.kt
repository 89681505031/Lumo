package app.lumo

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build

enum class CallAudioRouteKind { EARPIECE, SPEAKER, WIRED, BLUETOOTH, OTHER }

data class CallAudioRoute(
    val id:String,
    val label:String,
    val kind:CallAudioRouteKind
)

internal class CallAudioRouter(
    context:Context,
    private val preferSpeaker:Boolean
){
    private val app=context.applicationContext
    private val audioManager=app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val originalMode=audioManager.mode
    private val originalSpeaker=audioManager.isSpeakerphoneOn
    private var started=false
    private var focusRequest:AudioFocusRequest?=null

    fun start(){
        if(started)return
        started=true
        audioManager.mode=AudioManager.MODE_IN_COMMUNICATION
        requestFocus()
        if(Build.VERSION.SDK_INT>=31){
            val preferred=availableDevices().firstOrNull{
                it.type==(if(preferSpeaker)AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                    else AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)
            }
            if(preferred!=null)runCatching{audioManager.setCommunicationDevice(preferred)}
        }else{
            @Suppress("DEPRECATION")
            runCatching{audioManager.isSpeakerphoneOn=preferSpeaker}
        }
    }

    fun availableRoutes():List<CallAudioRoute>{
        if(!started)return emptyList()
        return if(Build.VERSION.SDK_INT>=31){
            availableDevices().map{device->
                CallAudioRoute(
                    id="device:"+device.id,
                    label=labelFor(device.type),
                    kind=kindFor(device.type)
                )
            }.distinctBy{it.id}
        }else{
            listOf(
                CallAudioRoute("legacy:earpiece","Телефон",CallAudioRouteKind.EARPIECE),
                CallAudioRoute("legacy:speaker","Динамик",CallAudioRouteKind.SPEAKER)
            )
        }
    }

    fun selectedRouteId():String?{
        if(!started)return null
        return if(Build.VERSION.SDK_INT>=31){
            runCatching{audioManager.communicationDevice}.getOrNull()?.let{"device:"+it.id}
        }else{
            @Suppress("DEPRECATION")
            if(audioManager.isSpeakerphoneOn)"legacy:speaker" else "legacy:earpiece"
        }
    }

    fun select(routeId:String):Boolean{
        if(!started)return false
        if(Build.VERSION.SDK_INT>=31){
            val id=routeId.removePrefix("device:").toIntOrNull()?:return false
            val device=availableDevices().firstOrNull{it.id==id}?:return false
            if(kindFor(device.type)==CallAudioRouteKind.BLUETOOTH &&
                app.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED
            )return false
            return runCatching{audioManager.setCommunicationDevice(device)}.getOrDefault(false)
        }
        @Suppress("DEPRECATION")
        return when(routeId){
            "legacy:earpiece"->{audioManager.isSpeakerphoneOn=false;true}
            "legacy:speaker"->{audioManager.isSpeakerphoneOn=true;true}
            else->false
        }
    }

    fun stop(){
        if(!started)return
        started=false
        if(Build.VERSION.SDK_INT>=31)runCatching{audioManager.clearCommunicationDevice()}
        @Suppress("DEPRECATION")
        runCatching{audioManager.isSpeakerphoneOn=originalSpeaker}
        runCatching{audioManager.mode=originalMode}
        abandonFocus()
    }

    private fun availableDevices():List<AudioDeviceInfo>{
        if(Build.VERSION.SDK_INT<31)return emptyList()
        return runCatching{audioManager.availableCommunicationDevices.filter{it.isSink}}
            .getOrDefault(emptyList())
    }

    private fun requestFocus(){
        val attrs=AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val request=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener{}
            .build()
        focusRequest=request
        runCatching{audioManager.requestAudioFocus(request)}
    }

    private fun abandonFocus(){
        focusRequest?.let{request->runCatching{audioManager.abandonAudioFocusRequest(request)}}
        focusRequest=null
    }

    companion object{
        private fun kindFor(type:Int)=when(type){
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE->CallAudioRouteKind.EARPIECE
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER->CallAudioRouteKind.SPEAKER
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET->CallAudioRouteKind.WIRED
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_HEARING_AID->CallAudioRouteKind.BLUETOOTH
            else->CallAudioRouteKind.OTHER
        }

        private fun labelFor(type:Int)=when(kindFor(type)){
            CallAudioRouteKind.EARPIECE->"Телефон"
            CallAudioRouteKind.SPEAKER->"Динамик"
            CallAudioRouteKind.WIRED->"Проводная гарнитура"
            CallAudioRouteKind.BLUETOOTH->"Bluetooth"
            CallAudioRouteKind.OTHER->"Другое аудиоустройство"
        }
    }
}
