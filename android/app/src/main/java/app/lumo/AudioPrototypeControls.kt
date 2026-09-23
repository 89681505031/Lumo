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
    onStop: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember(call.id) { mutableStateOf("Микрофон выключен") }
    var busy by remember(call.id) { mutableStateOf(false) }
    var engine by remember(call.id) { mutableStateOf<WebRtcAudioSession?>(null) }
    var outbound by remember(call.id) { mutableStateOf<Channel<Pair<String, JSONObject>>?>(null) }
    var senderJob by remember(call.id) { mutableStateOf<Job?>(null) }

    fun stop(message: String = "Микрофон выключен") {
        senderJob?.cancel()
        outbound?.close()
        engine?.stop()
        senderJob = null
        outbound = null
        engine = null
        busy = false
        status = message
        onStop()
    }

    fun start() {
        if (busy || engine != null || (activeAudioId != null && activeAudioId != call.id) ||
            call.status != "accepted" || call.kind != "audio") return
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED) {
            status = "Для теста нужен доступ к микрофону"
            return
        }
        busy = true
        onStart(call.id)
        scope.launch {
            try {
                // Never start native capture if the private TURN config is unavailable.
                val servers = withContext(Dispatchers.IO) {
                    Api.callClient.iceConfig(token, call.id)
                }
                val channel = Channel<Pair<String, JSONObject>>(Channel.UNLIMITED)
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
                                        if (e is CallsApiException && e.statusCode in listOf(403, 404, 409))
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
                            stop("Передача сигналов остановлена: соединение недоступно")
                        }
                    }
                }
                val created = WebRtcAudioSession(
                    context, servers, call.callerId == me.id,
                    onLocalSignal = { type, payload ->
                        // WebRTC invokes observer callbacks from its own threads.
                        channel.trySend(type to payload)
                    },
                    onState = { next ->
                        scope.launch { if (engine != null) status = next }
                    }
                )
                engine = created
                status = "Создаём тестовое аудиосоединение…"
                created.start()
            } catch (cancel: CancellationException) {
                stop()
                throw cancel
            } catch (error: Throwable) {
                stop(when (error) {
                    is CallsApiException -> when (error.code) {
                        "turn_unavailable" -> "На тестовом сервере не настроен приватный TURN"
                        "call_inactive" -> "Приглашение завершено"
                        else -> "Ошибка аудиоэтапа: ${error.code}"
                    }
                    else -> "Тестовое аудио не запустилось"
                })
            } finally {
                busy = false
            }
        }
    }

    val permission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted && call.status == "accepted") start()
        else status = "Микрофон не используется: доступ не предоставлен"
    }

    LaunchedEffect(engine, token, call.id) {
        val current = engine ?: return@LaunchedEffect
        var cursor = 0
        while (isActive && engine === current) {
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
        if (call.status != "accepted" && engine != null) stop("Сервер завершил вызов")
    }

    DisposableEffect(call.id) {
        onDispose {
            // Mandatory cleanup when navigating away or leaving the lab.
            senderJob?.cancel()
            outbound?.close()
            engine?.stop()
            onStop()
        }
    }

    Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Text(status, style = MaterialTheme.typography.bodySmall)
        if (engine != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { stop() }) { Text("Выключить микрофон") }
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
