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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.json.JSONObject
import java.util.UUID

/**
 * No automatic capture on receiving or accepting a call.
 * Both participants must separately tap the button and grant microphone access.
 */
@Composable
fun AudioPrototypeControls(
    token: String,
    me: User,
    call: LumoCall,
    activeAudioId: String?,
    onStart: (String) -> Unit,
    onStop: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    var status by remember(call.id) { mutableStateOf("Микрофон выключен") }
    var busy by remember(call.id) { mutableStateOf(false) }
    var engine by remember(call.id) { mutableStateOf<WebRtcAudioSession?>(null) }
    var outbound by remember(call.id) { mutableStateOf<Channel<Pair<String, JSONObject>>?>(null) }
    var senderJob by remember(call.id) { mutableStateOf<Job?>(null) }
    var startupJob by remember(call.id) { mutableStateOf<Job?>(null) }
    var muted by remember(call.id) { mutableStateOf(false) }
    var routeMenuOpen by remember(call.id) { mutableStateOf(false) }
    var routes by remember(call.id) { mutableStateOf<List<CallAudioRoute>>(emptyList()) }
    var selectedRouteId by remember(call.id) { mutableStateOf<String?>(null) }
    var pendingBluetoothRouteId by remember(call.id) { mutableStateOf<String?>(null) }
    var connected by remember(call.id) { mutableStateOf(false) }
    var connectedSince by remember(call.id) { mutableStateOf<Long?>(null) }
    var elapsedSeconds by remember(call.id) { mutableLongStateOf(0L) }
    val gate = remember(call.id) { AudioStartGate() }

    fun updateConnected(next:Boolean) {
        connected = next
        if (next && connectedSince == null) connectedSince = SystemClock.elapsedRealtime()
    }

    fun stop(message: String = "Микрофон выключен") {
        // Cancel in-flight TURN requests before disposing native audio resources.
        gate.invalidate()
        startupJob?.cancel()
        startupJob = null
        senderJob?.cancel()
        outbound?.close()
        engine?.stop()
        senderJob = null
        outbound = null
        engine = null
        muted = false
        routeMenuOpen = false
        routes = emptyList()
        selectedRouteId = null
        pendingBluetoothRouteId = null
        connected = false
        connectedSince = null
        elapsedSeconds = 0L
        busy = false
        status = message
        onStop(call.id)
    }

    fun start() {
        if (gate.isDisposed() || busy || engine != null || (activeAudioId != null && activeAudioId != call.id) ||
            call.status != "accepted" || call.kind != "audio") return
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED) {
            status = "Для звонка нужен доступ к микрофону"
            return
        }
        val ticket = gate.begin() ?: return
        busy = true
        onStart(call.id)
        startupJob = scope.launch {
            try {
                // Never start capture with stale TURN credentials after navigation
                // or if Android revokes the microphone permission while fetching.
                val servers = withContext(Dispatchers.IO) {
                    Api.callClient.iceConfig(token, call.id)
                }
                ensureActive()
                if (!gate.isCurrent(ticket) || call.status != "accepted" ||
                    context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
                    PackageManager.PERMISSION_GRANTED) {
                    if (gate.isCurrent(ticket)) stop("Вызов завершён или доступ к микрофону отозван")
                    return@launch
                }
                // Bounded queue: never accumulate unlimited ICE candidates when offline.
                val channel = Channel<Pair<String, JSONObject>>(64)
                outbound = channel
                senderJob = scope.launch(Dispatchers.IO) {
                    try {
                        for ((type, payload) in channel) {
                            // Keep the same id across network retries.
                            val id = UUID.randomUUID().toString()
                            var sent = false
                            var last: Throwable? = null
                            repeat(4) { attempt ->
                                if (!sent) {
                                    try {
                                        Api.callClient.sendSignal(token, call.id, id, type, payload)
                                        sent = true
                                    } catch (cancel: CancellationException) {
                                        throw cancel
                                    } catch (e: Throwable) {
                                        last = e
                                        if (e is CallsApiException && e.statusCode in listOf(401, 403, 404, 409, 429))
                                            throw e
                                        if (attempt < 3) delay(1000L * (attempt + 1))
                                    }
                                }
                            }
                            if (!sent) throw last ?: IllegalStateException("Could not send signal")
                        }
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (error: Throwable) {
                        withContext(Dispatchers.Main) {
                            if (gate.isCurrent(ticket)) stop("Передача сигналов остановлена")
                        }
                    }
                }
                val created = WebRtcAudioSession(
                    context, servers, call.callerId == me.id,
                    onLocalSignal = { type, payload ->
                        // WebRTC invokes observer callbacks from its own threads.
                        if (!channel.trySend(type to payload).isSuccess) {
                            scope.launch {
                                if (gate.isCurrent(ticket)) stop("Очередь сигналов переполнена")
                            }
                        }
                    },
                    onState = { next ->
                        scope.launch {
                            if (gate.isCurrent(ticket) && engine != null) status = next
                        }
                    },
                    onConnectionState = { next ->
                        scope.launch {
                            if (gate.isCurrent(ticket) && engine != null) updateConnected(next)
                        }
                    }
                )
                // Constructor can return after a concurrent hangup. Never keep a
                // stale native microphone track alive.
                if (!gate.isCurrent(ticket)) {
                    created.stop()
                    return@launch
                }
                engine = created
                routes = created.availableAudioRoutes()
                selectedRouteId = created.selectedAudioRouteId()
                status = "Создаём аудиосоединение…"
                created.start()
            } catch (cancel: CancellationException) {
                if (gate.isCurrent(ticket)) stop()
                throw cancel
            } catch (error: Throwable) {
                if (gate.isCurrent(ticket)) stop(when (error) {
                    is CallsApiException -> when (error.code) {
                        "turn_unavailable" -> "Сервер звонков временно недоступен"
                        "call_inactive" -> "Приглашение завершено"
                        else -> "Ошибка аудиоэтапа: ${error.code}"
                    }
                    else -> "Аудиозвонок не запустился"
                })
            } finally {
                if (gate.isCurrent(ticket)) {
                    busy = false
                    startupJob = null
                }
            }
        }
    }

    val bluetoothPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val routeId = pendingBluetoothRouteId
        pendingBluetoothRouteId = null
        if (granted && routeId != null) {
            val current = engine
            if (current != null && current.selectAudioRoute(routeId)) {
                selectedRouteId = current.selectedAudioRouteId()
                routes = current.availableAudioRoutes()
                status = "Аудиовыход переключён"
            } else status = "Не удалось переключить Bluetooth-аудио"
        } else if (!granted) status = "Bluetooth-аудио не выбрано: доступ не предоставлен"
    }

    fun selectRoute(route:CallAudioRoute) {
        val current=engine?:return
        if (route.kind==CallAudioRouteKind.BLUETOOTH && Build.VERSION.SDK_INT>=31 &&
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED
        ) {
            pendingBluetoothRouteId=route.id
            bluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
            return
        }
        if (current.selectAudioRoute(route.id)) {
            selectedRouteId=current.selectedAudioRouteId()
            routes=current.availableAudioRoutes()
            status="Аудиовыход переключён"
        } else status="Не удалось переключить аудиовыход"
    }

    val permission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!gate.isDisposed()) {
            if (granted && call.status == "accepted") start()
            else status = "Микрофон не используется: доступ не предоставлен"
        }
    }

    LaunchedEffect(engine, connectedSince) {
        while (engine != null && connectedSince != null && isActive) {
            elapsedSeconds = ((SystemClock.elapsedRealtime() - connectedSince!!) / 1000L)
                .coerceAtLeast(0L)
            delay(1_000)
        }
    }

    LaunchedEffect(engine, token, call.id) {
        val current = engine ?: return@LaunchedEffect
        var cursor = 0
        try {
            val history = withContext(Dispatchers.IO) {
                Api.callClient.signalHistory(token, call.id)
            }
            cursor = history.lastOrNull()?.seq ?: 0
            val sessionId = if (call.callerId == me.id) current.mediaSessionId()
                else history.lastOrNull { it.type == "offer" }
                    ?.payload?.optString("mediaSessionId")?.takeIf { it.isNotBlank() }
            if (sessionId != null) {
                for (signal in history) {
                    if (signal.from != me.id &&
                        signal.payload.optString("mediaSessionId") == sessionId
                    ) current.apply(signal)
                }
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            status = "Не удалось восстановить состояние сигнализации"
        }
        while (isActive && engine === current) {
            if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED) {
                stop("Android отозвал разрешение на микрофон")
                break
            }
            try {
                val signals = withContext(Dispatchers.IO) {
                    Api.callClient.signals(token, call.id, cursor)
                }
                for (signal in signals) {
                    if (signal.from != me.id) current.apply(signal)
                    cursor = signal.seq
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: CallsApiException) {
                if (error.statusCode in listOf(403, 404, 409)) {
                    stop("Сервер завершил вызов")
                    break
                }
                status = "Повторяем подключение к сигнализации…"
            } catch (error: Throwable) {
                status = "Нет связи с сервером сигнализации"
            }
            delay(1400)
        }
    }

    LaunchedEffect(call.status) {
        if (call.status != "accepted" && (engine != null || busy)) {
            stop("Сервер завершил вызов")
        }
    }

    DisposableEffect(call.id, lifecycleOwner) {
        // A Compose screen can stay alive after pressing Home. Debug audio must
        // stop immediately when its host activity is no longer visible.
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && (engine != null || busy)) {
                stop("Аудио остановлено: приложение свернуто")
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            // Invalidate BEFORE cleanup: any late TURN response cannot reopen audio.
            gate.dispose()
            startupJob?.cancel()
            senderJob?.cancel()
            outbound?.close()
            engine?.stop()
            onStop(call.id)
        }
    }

    Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Row(
            Modifier.fillMaxWidth().lumoGlass(18).padding(horizontal=12.dp,vertical=9.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    when {
                        connected -> "Соединено · " + formatCallElapsed(elapsedSeconds)
                        connectedSince != null -> "Связь прервана · " + formatCallElapsed(elapsedSeconds)
                        busy -> "Подключение…"
                        else -> "Аудиоканал выключен"
                    },
                    style = MaterialTheme.typography.labelLarge
                )
                Text(status, style = MaterialTheme.typography.bodySmall)
            }
            if (engine != null) {
                Text(
                    routes.firstOrNull{it.id==selectedRouteId}?.label ?: "Аудиовыход",
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        if (engine != null) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        muted = !muted
                        engine?.setMuted(muted)
                    },
                    modifier = Modifier.weight(1f)
                ) { Text(if (muted) "Включить микрофон" else "Без звука") }
                Box(Modifier.weight(1f)) {
                    OutlinedButton(
                        onClick = {
                            routes = engine?.availableAudioRoutes().orEmpty()
                            selectedRouteId = engine?.selectedAudioRouteId()
                            routeMenuOpen = true
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Аудиовыход") }
                    DropdownMenu(
                        expanded = routeMenuOpen,
                        onDismissRequest = { routeMenuOpen = false }
                    ) {
                        routes.forEach { route ->
                            DropdownMenuItem(
                                text = { Text((if (route.id == selectedRouteId) "✓ " else "") + route.label) },
                                onClick = {
                                    routeMenuOpen = false
                                    selectRoute(route)
                                }
                            )
                        }
                    }
                }
            }
            TextButton(onClick = { stop("Аудиосоединение отключено") }) {
                Text("Отключить аудио")
            }
        } else if (busy) {
            OutlinedButton(onClick = { stop("Подключение отменено") }) {
                Text("Отменить подключение")
            }
        } else if (activeAudioId == null || activeAudioId == call.id) {
            OutlinedButton(
                onClick = {
                    if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                        PackageManager.PERMISSION_GRANTED) start()
                    else permission.launch(Manifest.permission.RECORD_AUDIO)
                },
                enabled = !busy && call.status == "accepted"
            ) {
                Text(if (busy) "Подключаем..." else "Включить аудио")
            }
        } else {
            Text("Сначала завершите другое аудиосоединение.")
        }
        Text(
            "Аудио включается только вручную после принятия вызова. " +
                "При сворачивании Lumo микрофон и WebRTC-сессия останавливаются.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}
