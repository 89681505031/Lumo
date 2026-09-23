package app.lumo

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun callError(error: Throwable): String = when (error) {
    is SessionExpiredException -> "Сессия истекла. Войдите заново."
    is CallsUnavailableException -> "Серверная сигнализация отключена."
    is CallsApiException -> when (error.code) {
        "user_blocked" -> "Звонок недоступен: переписка заблокирована."
        "call_rate_limited" -> "Слишком много приглашений. Повторите позже."
        "call_busy" -> "Один из участников уже занят другим вызовом."
        "call_inactive", "invalid_call_state" -> "Приглашение уже завершено или устарело."
        "recipient_not_found" -> "Пользователь больше не найден."
        "not_call_recipient" -> "Принять вызов может только получатель."
        else -> "Ошибка сервера (${error.statusCode})."
    }
    else -> "Проблема с подключением. Проверьте сеть."
}

private fun callState(call: LumoCall): String = when (call.status) {
    "ringing" -> "Ожидает ответа"
    "accepted" -> "Принято — медиа не подключено"
    "declined" -> "Отклонено"
    "ended" -> "Завершено"
    "expired" -> "Время ожидания истекло"
    else -> "Недоступно"
}

/**
 * Debug-only lab: microphone requested only after an accepted AUDIO call and
 * both participants opt in. Video and background calling remain unavailable.
 */
@Composable
fun CallInvitationsLab(token: String, me: User) {
    val scope = rememberCoroutineScope()
    var enabled by remember(token) { mutableStateOf<Boolean?>(null) }
    var calls by remember(token) { mutableStateOf<List<LumoCall>>(emptyList()) }
    var people by remember(token) { mutableStateOf<List<User>>(emptyList()) }
    var busy by remember(token) { mutableStateOf<String?>(null) }
    var errorText by remember(token) { mutableStateOf("") }
    var refresh by remember(token) { mutableIntStateOf(0) }
    var refreshPeople by remember(token) { mutableIntStateOf(0) }
    var activeAudioId by remember(token) { mutableStateOf<String?>(null) }

    LaunchedEffect(token, refresh) {
        while (true) {
            val result = runCatching { withContext(Dispatchers.IO) { Api.callClient.list(token) } }
            result.onSuccess {
                enabled = true
                calls = it
                errorText = ""
            }.onFailure {
                if (it is CallsUnavailableException) {
                    enabled = false
                    errorText = ""
                } else {
                    if (enabled == null) enabled = true // Retry on transient failures.
                    errorText = callError(it)
                }
            }
            if (enabled == false) break
            delay(4_000)
        }
    }
    LaunchedEffect(token, enabled, refreshPeople) {
        if (enabled == true) {
            runCatching { withContext(Dispatchers.IO) { Api.users(token, "") } }
                .onSuccess { people = it }
                .onFailure { errorText = callError(it) }
        }
    }

    fun updateCall(updated: LumoCall) {
        calls = (listOf(updated) + calls.filterNot { it.id == updated.id })
            .sortedByDescending { it.id == updated.id }
    }

    fun perform(key: String, operation: () -> LumoCall) {
        if (busy != null) return
        busy = key
        errorText = ""
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { operation() } }
                .onSuccess { updateCall(it) }
                .onFailure {
                    if (it is CallsUnavailableException) enabled = false
                    errorText = callError(it)
                }
            busy = null
        }
    }

    Column(Modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
            Column(Modifier.fillMaxWidth().padding(14.dp)) {
                Text("Лаборатория звонков", fontWeight = FontWeight.Bold)
                Text(
                    "Тестовая передача аудио доступна только после принятия вызова, отдельного " +
                        "согласия обоих участников и настройки приватного TURN. " +
                        "Видеопередачи и фоновых звонков пока нет.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        when (enabled) {
            null -> {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("Проверяем доступность сервера…", Modifier.padding(16.dp))
            }
            false -> {
                Text("Тестовые звонки на сервере выключены.", Modifier.padding(16.dp))
                OutlinedButton(
                    onClick = { enabled = null; refresh++ },
                    modifier = Modifier.padding(horizontal = 16.dp)
                ) { Text("Проверить снова") }
            }
            true -> {
                if (errorText.isNotBlank()) {
                    Text(
                        errorText,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(12.dp)
                    )
                    TextButton(onClick = { refresh++; refreshPeople++ }) {
                        Text("Повторить проверку")
                    }
                }
                val active = calls.filter { it.status in listOf("ringing", "accepted") }
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item {
                        Text("Приглашения", style = MaterialTheme.typography.titleLarge)
                        if (active.isEmpty()) {
                            Text(
                                "Активных приглашений пока нет. " +
                                    "Список обновляется только пока открыта эта вкладка.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                    items(active, key = { it.id }) { call ->
                        val isIncoming = call.calleeId == me.id
                        val participantId = if (isIncoming) call.callerId else call.calleeId
                        val other = people.firstOrNull { it.id == participantId }
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp)) {
                                Text(
                                    (if (isIncoming) "Входящее" else "Исходящее") + " " +
                                        (if (call.kind == "video") "видеоприглашение" else "аудиоприглашение"),
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(other?.displayName ?: "Пользователь")
                                Text(
                                    callState(call),
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Spacer(Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (isIncoming && call.status == "ringing") {
                                        Button(
                                            onClick = {
                                                perform(call.id + "accept") {
                                                    Api.callClient.respond(token, call.id, "accept")
                                                }
                                            },
                                            enabled = busy == null
                                        ) { Text("Принять тест") }
                                        OutlinedButton(
                                            onClick = {
                                                perform(call.id + "decline") {
                                                    Api.callClient.respond(token, call.id, "decline")
                                                }
                                            },
                                            enabled = busy == null
                                        ) { Text("Отклонить") }
                                    } else {
                                        OutlinedButton(
                                            onClick = {
                                                perform(call.id + "end") {
                                                    Api.callClient.respond(token, call.id, "end")
                                                }
                                            },
                                            enabled = busy == null
                                        ) { Text("Завершить") }
                                    }
                                }
                                if (call.kind == "audio" && call.status == "accepted") {
                                    AudioPrototypeControls(
                                        token = token, me = me, call = call,
                                        activeAudioId = activeAudioId,
                                        onStart = { id -> activeAudioId = id },
                                        onStop = { id -> if (activeAudioId == id) activeAudioId = null }
                                    )
                                } else if (call.kind == "video" && call.status == "accepted") {
                                    Text(
                                        "Видеосоединение пока не реализовано.",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }
                    }
                    item {
                        HorizontalDivider(Modifier.padding(vertical = 10.dp))
                        Text("Отправить тестовое приглашение", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Пользователь должен открыть эту вкладку на своём телефоне.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (people.isEmpty()) {
                            Text("Контактов нет или список ещё загружается.")
                            TextButton(onClick = { refreshPeople++ }) {
                                Text("Обновить пользователей")
                            }
                        }
                    }
                    items(people, key = { "contact-" + it.id }) { person ->
                        if (person.id != me.id) {
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(12.dp)) {
                                    Text(person.displayName, fontWeight = FontWeight.SemiBold)
                                    Text("@${person.username}", style = MaterialTheme.typography.bodySmall)
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(
                                            onClick = {
                                                perform(person.id + "audio") {
                                                    Api.callClient.invite(token, person.id, "audio")
                                                }
                                            },
                                            enabled = busy == null
                                        ) { Text("Тест аудио") }
                                        OutlinedButton(
                                            onClick = {
                                                perform(person.id + "video") {
                                                    Api.callClient.invite(token, person.id, "video")
                                                }
                                            },
                                            enabled = busy == null
                                        ) { Text("Тест видео") }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
