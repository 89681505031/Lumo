package app.lumo

import android.Manifest
import android.content.pm.PackageManager
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
    val gate = remember(call.id) { AudioStartGate() }

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
        busy = false
        status = message
        onStop(call.id)
    }

    fun start() {
        if (gate.isDisposed() || busy || engine != null || (activeAudioId != null && activeAudioId != call.id) ||
            call.status != "accepted" || call.kind != "audio") return
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED) {
            status = "Для теста нужен доступ к микрофону"
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
                    }
                )
                // Constructor can return after a concurrent hangup. Never keep a
                // stale native microphone track alive.
                if (!gate.isCurrent(ticket)) {
                    created.stop()
                    return@launch
                }
                engine = created
                status = "Создаём тестовое аудиосоединение…"
                created.start()
            } catch (cancel: CancellationException) {
                if (gate.isCurrent(ticket)) stop()
                throw cancel
            } catch (error: Throwable) {
                if (gate.isCurrent(ticket)) stop(when (error) {
                    is CallsApiException -> when (error.code) {
                        "turn_unavailable" -> "На тестовом сервере не настроен приватный TURN"
                        "call_inactive" -> "Приглашение завершено"
                        else -> "Ошибка аудиоэтапа: ${error.code}"
                    }
                    else -> "Тестовое аудио не запустилось"
                })
            } finally {
                if (gate.isCurrent(ticket)) {
                    busy = false
                    startupJob = null
                }
            }
        }
    }

    val permission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!gate.isDisposed()) {
            if (granted && call.status == "accepted") start()
            else status = "Микрофон не используется: доступ не предоставлен"
        }
    }

    LaunchedEffect(engine, token, call.id) {
        val current = engine ?: return@LaunchedEffect
        var cursor = 0
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
                    stop("Сервер завершил тестовый вызов")
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
                stop("Тестовое аудио остановлено: приложение свернуто")
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
        Text(status, style = MaterialTheme.typography.bodySmall)
        if (engine != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { stop() }) { Text("Выключить микрофон") }
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
                Text(if (busy) "Подключаем..." else "Включить тест аудио")
            }
        } else {
            Text("Сначала завершите другое тестовое аудиосоединение.")
        }
        Text(
            "Эксперимент: оба участника должны отдельно включить аудио. " +
                "Нет фоновых вызовов, гарантированной связи и видеопередачи.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}
