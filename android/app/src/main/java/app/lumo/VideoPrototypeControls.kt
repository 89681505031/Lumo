package app.lumo

import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.json.JSONObject
import org.webrtc.SurfaceViewRenderer
import java.util.UUID

/**
 * Explicit staging video transport. Accepting a call never starts capture.
 * Each participant must press the button and grant CAMERA + RECORD_AUDIO.
 */
@Composable
fun VideoPrototypeControls(
    token:String,
    me:User,
    call:LumoCall,
    activeMediaId:String?,
    onStart:(String)->Unit,
    onStop:(String)->Unit
){
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    val lifecycleOwner=LocalLifecycleOwner.current
    val gate=remember(call.id){AudioStartGate()}

    var status by remember(call.id){mutableStateOf("Камера и микрофон выключены")}
    var busy by remember(call.id){mutableStateOf(false)}
    var engine by remember(call.id){mutableStateOf<WebRtcVideoSession?>(null)}
    var outbound by remember(call.id){mutableStateOf<Channel<Pair<String,JSONObject>>?>(null)}
    var senderJob by remember(call.id){mutableStateOf<Job?>(null)}
    var startupJob by remember(call.id){mutableStateOf<Job?>(null)}
    var muted by remember(call.id){mutableStateOf(false)}
    var cameraEnabled by remember(call.id){mutableStateOf(true)}
    var routeMenuOpen by remember(call.id){mutableStateOf(false)}
    var routes by remember(call.id){mutableStateOf<List<CallAudioRoute>>(emptyList())}
    var selectedRouteId by remember(call.id){mutableStateOf<String?>(null)}
    var pendingBluetoothRouteId by remember(call.id){mutableStateOf<String?>(null)}
    var connected by remember(call.id){mutableStateOf(false)}
    var connectedSince by remember(call.id){mutableStateOf<Long?>(null)}
    var elapsedSeconds by remember(call.id){mutableLongStateOf(0L)}
    var renderGeneration by remember(call.id){mutableIntStateOf(0)}

    fun updateConnected(next:Boolean){
        connected=next
        if(next&&connectedSince==null)connectedSince=SystemClock.elapsedRealtime()
    }
    var localRenderer by remember(call.id,renderGeneration){mutableStateOf<SurfaceViewRenderer?>(null)}
    var remoteRenderer by remember(call.id,renderGeneration){mutableStateOf<SurfaceViewRenderer?>(null)}

    fun hasPermissions():Boolean=
        context.checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED &&
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED

    fun stop(message:String="Камера и микрофон выключены",rebuildRenderers:Boolean=true){
        gate.invalidate()
        startupJob?.cancel()
        startupJob=null
        senderJob?.cancel()
        outbound?.close()
        engine?.stop()
        senderJob=null
        outbound=null
        engine=null
        muted=false
        cameraEnabled=true
        routeMenuOpen=false
        routes=emptyList()
        selectedRouteId=null
        pendingBluetoothRouteId=null
        connected=false
        connectedSince=null
        elapsedSeconds=0L
        busy=false
        status=message
        onStop(call.id)
        if(rebuildRenderers){
            localRenderer=null
            remoteRenderer=null
            renderGeneration++
        }
    }

    fun start(){
        if(gate.isDisposed()||busy||engine!=null||
            (activeMediaId!=null&&activeMediaId!=call.id)||
            call.status!="accepted"||call.kind!="video")return
        if(!hasPermissions()){
            status="Для видео нужны разрешения камеры и микрофона"
            return
        }
        val local=localRenderer
        val remote=remoteRenderer
        if(local==null||remote==null){
            status="Подготавливаем видеоповерхность…"
            return
        }
        val ticket=gate.begin()?:return
        busy=true
        onStart(call.id)
        startupJob=scope.launch{
            try{
                val servers=withContext(Dispatchers.IO){
                    Api.callClient.iceConfig(token,call.id)
                }
                ensureActive()
                if(!gate.isCurrent(ticket)||call.status!="accepted"||!hasPermissions()){
                    if(gate.isCurrent(ticket))stop("Вызов завершён или разрешения отозваны")
                    return@launch
                }
                val channel=Channel<Pair<String,JSONObject>>(64)
                outbound=channel
                senderJob=scope.launch(Dispatchers.IO){
                    try{
                        for((type,payload) in channel){
                            val id=UUID.randomUUID().toString()
                            var sent=false
                            var last:Throwable?=null
                            repeat(4){attempt->
                                if(!sent){
                                    try{
                                        Api.callClient.sendSignal(
                                            token,call.id,id,type,payload
                                        )
                                        sent=true
                                    }catch(cancel:CancellationException){
                                        throw cancel
                                    }catch(error:Throwable){
                                        last=error
                                        if(error is CallsApiException &&
                                            error.statusCode in listOf(401,403,404,409,429))
                                            throw error
                                        if(attempt<3)delay(1000L*(attempt+1))
                                    }
                                }
                            }
                            if(!sent)throw last?:IllegalStateException("Could not send signal")
                        }
                    }catch(cancel:CancellationException){
                        throw cancel
                    }catch(error:Throwable){
                        withContext(Dispatchers.Main){
                            if(gate.isCurrent(ticket))
                                stop("Передача видеосигналов остановлена")
                        }
                    }
                }
                val created=WebRtcVideoSession(
                    context=context,
                    iceServers=servers,
                    caller=call.callerId==me.id,
                    localRenderer=local,
                    remoteRenderer=remote,
                    onLocalSignal={type,payload->
                        if(!channel.trySend(type to payload).isSuccess){
                            scope.launch{
                                if(gate.isCurrent(ticket))
                                    stop("Очередь видеосигналов переполнена")
                            }
                        }
                    },
                    onState={next->
                        scope.launch{
                            if(gate.isCurrent(ticket)&&engine!=null)status=next
                        }
                    },
                    onConnectionState={next->
                        scope.launch{
                            if(gate.isCurrent(ticket)&&engine!=null)updateConnected(next)
                        }
                    }
                )
                if(!gate.isCurrent(ticket)){
                    created.stop()
                    return@launch
                }
                engine=created
                routes=created.availableAudioRoutes()
                selectedRouteId=created.selectedAudioRouteId()
                status="Создаём приватное видеосоединение…"
                created.start()
            }catch(cancel:CancellationException){
                if(gate.isCurrent(ticket))stop()
                throw cancel
            }catch(error:Throwable){
                if(gate.isCurrent(ticket))stop(
                    when(error){
                        is CallsApiException->when(error.code){
                            "turn_unavailable"->"На сервере не настроен приватный TURN"
                            "call_inactive"->"Вызов уже завершён"
                            else->"Ошибка видеоэтапа: "+error.code
                        }
                        else->"Тестовое видео не запустилось"
                    }
                )
            }finally{
                if(gate.isCurrent(ticket)){
                    busy=false
                    startupJob=null
                }
            }
        }
    }

    val bluetoothPermission=rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ){granted->
        val routeId=pendingBluetoothRouteId
        pendingBluetoothRouteId=null
        if(granted&&routeId!=null){
            val current=engine
            if(current!=null&&current.selectAudioRoute(routeId)){
                selectedRouteId=current.selectedAudioRouteId()
                routes=current.availableAudioRoutes()
                status="Аудиовыход переключён"
            }else status="Не удалось переключить Bluetooth-аудио"
        }else if(!granted)status="Bluetooth-аудио не выбрано: доступ не предоставлен"
    }

    fun selectRoute(route:CallAudioRoute){
        val current=engine?:return
        if(route.kind==CallAudioRouteKind.BLUETOOTH&&Build.VERSION.SDK_INT>=31&&
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED
        ){
            pendingBluetoothRouteId=route.id
            bluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
            return
        }
        if(current.selectAudioRoute(route.id)){
            selectedRouteId=current.selectedAudioRouteId()
            routes=current.availableAudioRoutes()
            status="Аудиовыход переключён"
        }else status="Не удалось переключить аудиовыход"
    }

    val permission=rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ){result->
        if(!gate.isDisposed()){
            val granted=result[Manifest.permission.CAMERA]==true &&
                result[Manifest.permission.RECORD_AUDIO]==true
            if(granted&&call.status=="accepted")start()
            else status="Камера и микрофон не используются: разрешения не предоставлены"
        }
    }

    LaunchedEffect(engine,connectedSince){
        while(engine!=null&&connectedSince!=null&&isActive){
            elapsedSeconds=((SystemClock.elapsedRealtime()-connectedSince!!)/1000L)
                .coerceAtLeast(0L)
            delay(1_000)
        }
    }

    LaunchedEffect(engine,token,call.id){
        val current=engine?:return@LaunchedEffect
        var cursor=0
        try{
            val history=withContext(Dispatchers.IO){
                Api.callClient.signalHistory(token,call.id)
            }
            cursor=history.lastOrNull()?.seq?:0
            val sessionId=if(call.callerId==me.id)current.mediaSessionId()
                else history.lastOrNull{it.type=="offer"}
                    ?.payload?.optString("mediaSessionId")?.takeIf{it.isNotBlank()}
            if(sessionId!=null){
                for(signal in history){
                    if(signal.from!=me.id &&
                        signal.payload.optString("mediaSessionId")==sessionId)
                        current.apply(signal)
                }
            }
        }catch(cancel:CancellationException){
            throw cancel
        }catch(error:Throwable){
            status="Не удалось восстановить состояние видеосигнализации"
        }
        while(isActive&&engine===current){
            if(!hasPermissions()){
                stop("Android отозвал разрешение камеры или микрофона")
                break
            }
            try{
                val signals=withContext(Dispatchers.IO){
                    Api.callClient.signals(token,call.id,cursor)
                }
                for(signal in signals){
                    if(signal.from!=me.id)current.apply(signal)
                    cursor=signal.seq
                }
            }catch(cancel:CancellationException){
                throw cancel
            }catch(error:CallsApiException){
                if(error.statusCode in listOf(403,404,409)){
                    stop("Сервер завершил видеовызов")
                    break
                }
                status="Повторяем подключение к сигнализации…"
            }catch(error:Throwable){
                status="Нет связи с сервером сигнализации"
            }
            delay(1400)
        }
    }

    LaunchedEffect(call.status){
        if(call.status!="accepted"&&(engine!=null||busy))
            stop("Сервер завершил вызов")
    }

    DisposableEffect(call.id,lifecycleOwner){
        val observer=LifecycleEventObserver{_,event->
            if(event==Lifecycle.Event.ON_STOP&&(engine!=null||busy))
                stop("Видео остановлено: приложение свернуто")
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose{
            lifecycleOwner.lifecycle.removeObserver(observer)
            gate.dispose()
            startupJob?.cancel()
            senderJob?.cancel()
            outbound?.close()
            engine?.stop()
            onStop(call.id)
        }
    }

    Column(Modifier.fillMaxWidth().padding(top=10.dp)){
        key(renderGeneration){
            Box(
                Modifier.fillMaxWidth().height(230.dp)
            ){
                AndroidView(
                    factory={ctx->
                        SurfaceViewRenderer(ctx).also{remoteRenderer=it}
                    },
                    modifier=Modifier.fillMaxSize()
                )
                AndroidView(
                    factory={ctx->
                        SurfaceViewRenderer(ctx).apply{
                            setZOrderMediaOverlay(true)
                        }.also{localRenderer=it}
                    },
                    modifier=Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .width(96.dp)
                        .height(128.dp)
                )
                if(engine==null){
                    Box(
                        Modifier.fillMaxSize(),
                        contentAlignment=Alignment.Center
                    ){
                        Text(
                            "Видео выключено",
                            style=MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(7.dp))
        Row(
            Modifier.fillMaxWidth().lumoGlass(18).padding(horizontal=12.dp,vertical=9.dp),
            horizontalArrangement=Arrangement.SpaceBetween
        ){
            Column(Modifier.weight(1f)){
                Text(
                    when{
                        connected->"Соединено · "+formatCallElapsed(elapsedSeconds)
                        connectedSince!=null->"Связь прервана · "+formatCallElapsed(elapsedSeconds)
                        busy->"Подключение…"
                        else->"Видеоканал выключен"
                    },
                    style=MaterialTheme.typography.labelLarge
                )
                Text(status,style=MaterialTheme.typography.bodySmall)
            }
            if(engine!=null){
                Text(
                    routes.firstOrNull{it.id==selectedRouteId}?.label ?: "Аудиовыход",
                    style=MaterialTheme.typography.labelMedium
                )
            }
        }

        if(engine!=null){
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement=Arrangement.spacedBy(7.dp)
            ){
                OutlinedButton(
                    onClick={
                        muted=!muted
                        engine?.setMuted(muted)
                    },
                    modifier=Modifier.weight(1f)
                ){Text(if(muted)"Микрофон ✓" else "Без звука")}
                OutlinedButton(
                    onClick={
                        cameraEnabled=!cameraEnabled
                        engine?.setCameraEnabled(cameraEnabled)
                    },
                    modifier=Modifier.weight(1f)
                ){Text(if(cameraEnabled)"Камера ✓" else "Камера выкл.")}
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement=Arrangement.spacedBy(7.dp)
            ){
                OutlinedButton(
                    onClick={engine?.switchCamera()},
                    modifier=Modifier.weight(1f)
                ){Text("Сменить камеру")}
                Box(Modifier.weight(1f)){
                    OutlinedButton(
                        onClick={
                            routes=engine?.availableAudioRoutes().orEmpty()
                            selectedRouteId=engine?.selectedAudioRouteId()
                            routeMenuOpen=true
                        },
                        modifier=Modifier.fillMaxWidth()
                    ){Text("Аудиовыход")}
                    DropdownMenu(
                        expanded=routeMenuOpen,
                        onDismissRequest={routeMenuOpen=false}
                    ){
                        routes.forEach{route->
                            DropdownMenuItem(
                                text={Text((if(route.id==selectedRouteId)"✓ " else "")+route.label)},
                                onClick={
                                    routeMenuOpen=false
                                    selectRoute(route)
                                }
                            )
                        }
                    }
                }
            }
            TextButton(onClick={stop("Видеосоединение отключено")}){
                Text("Отключить видео")
            }
        }else if(busy){
            OutlinedButton(onClick={stop("Подключение отменено")}){
                Text("Отменить подключение")
            }
        }else if(activeMediaId==null||activeMediaId==call.id){
            OutlinedButton(
                onClick={
                    if(hasPermissions())start()
                    else permission.launch(arrayOf(
                        Manifest.permission.CAMERA,
                        Manifest.permission.RECORD_AUDIO
                    ))
                },
                enabled=call.status=="accepted"&&!busy
            ){
                Text("Включить видео и аудио")
            }
        }else{
            Text("Сначала завершите другое активное медиасоединение.")
        }

        Text(
            "Камера и микрофон включаются только после нажатия этой кнопки. " +
                "При сворачивании Lumo захват сразу останавливается; ICE работает только через приватный TURN.",
            style=MaterialTheme.typography.bodySmall
        )
    }
}
