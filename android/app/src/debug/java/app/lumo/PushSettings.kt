package app.lumo

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private suspend fun getFcmToken(): String = suspendCancellableCoroutine { continuation ->
    FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
        if (continuation.isActive) {
            if (task.isSuccessful && !task.result.isNullOrBlank()) {
                continuation.resume(task.result)
            } else {
                continuation.resumeWithException(IllegalStateException("FCM unavailable"))
            }
        }
    }
}

@Composable
fun PushSettings(session: String, me: User) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var enabled by remember(session, me.id) {
        mutableStateOf(PushOptState.consented(context, me.id, session))
    }
    var busy by remember(session, me.id) { mutableStateOf(false) }
    var notice by remember(session, me.id) { mutableStateOf("") }
    var requestEnable by remember(session, me.id) { mutableIntStateOf(0) }

    val permission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) requestEnable++
        else notice = "Уведомления не включены: разрешение не предоставлено."
    }

    LaunchedEffect(requestEnable) {
        if (requestEnable == 0 || !BuildConfig.LUMO_FCM_CONFIGURED || busy || enabled) return@LaunchedEffect
        busy = true
        notice = ""
        val result = runCatching {
            withContext(Dispatchers.IO) {
                // No FCM token or Firebase auto-init before a user's click.
                val messaging = FirebaseMessaging.getInstance()
                messaging.isAutoInitEnabled = true
                val fcmToken = getFcmToken()
                check(PushOptState.permissionGranted(context)) { "Notifications permission revoked" }
                check(
                    context.getSharedPreferences("lumo_session", Context.MODE_PRIVATE)
                        .getString("token", null) == session
                ) { "Session changed before token registration" }
                LumoPushApi.register(session, fcmToken)
                if (context.getSharedPreferences("lumo_session", Context.MODE_PRIVATE)
                        .getString("token", null) != session) {
                    runCatching { LumoPushApi.revoke(session) }
                    throw IllegalStateException("Session changed during token registration")
                }
                PushOptState.enable(context, me.id, session)
                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.createNotificationChannel(
                    NotificationChannel("lumo_messages", "Сообщения Lumo", NotificationManager.IMPORTANCE_DEFAULT)
                )
            }
        }
        result.onSuccess {
            enabled = true
            notice = "Тестовые уведомления включены только для текущего аккаунта."
        }.onFailure {
            withContext(Dispatchers.IO) {
                // An unsuccessful opt-in must not leave automatic FCM registration active.
                runCatching {
                    val messaging = FirebaseMessaging.getInstance()
                    messaging.isAutoInitEnabled = false
                    messaging.deleteToken()
                }
            }
            notice = "Не удалось подключить уведомления. Проверьте сервер и Firebase."
        }
        busy = false
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(18.dp)) {
            Text("Уведомления — лаборатория", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            if (!BuildConfig.LUMO_FCM_CONFIGURED) {
                Text(
                    "Firebase для отдельного тестового сервера ещё не настроен. " +
                        "В обычной версии Lumo эта функция отключена.",
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                Text(
                    "Необязательно. На экране блокировки будет только «Новое сообщение», " +
                        "без имени отправителя и текста. Отключить можно здесь в любой момент.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(12.dp))
                if (enabled) {
                    Text(if (PushOptState.permissionGranted(context)) "Включено" else
                        "Разрешение отключено в настройках Android")
                    Button(
                        onClick = {
                            if (busy) return@Button
                            busy = true
                            notice = ""
                            scope.launch {
                                runCatching {
                                    withContext(Dispatchers.IO) { LumoPushApi.revoke(session) }
                                }.onSuccess {
                                    PushOptState.clear(context)
                                    enabled = false
                                    withContext(Dispatchers.IO) {
                                        runCatching {
                                            val messaging = FirebaseMessaging.getInstance()
                                            messaging.isAutoInitEnabled = false
                                            messaging.deleteToken()
                                        }
                                    }
                                    notice = "Уведомления отключены."
                                }.onFailure {
                                    notice = "Не удалось отключить уведомления на сервере. " +
                                        "Проверьте подключение и повторите попытку."
                                }
                                busy = false
                            }
                        }, enabled = !busy
                    ) { Text(if (busy) "Отключаем…" else "Отключить уведомления") }
                } else {
                    Button(
                        onClick = {
                            if (Build.VERSION.SDK_INT >= 33 &&
                                !PushOptState.permissionGranted(context)) {
                                permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                requestEnable++
                            }
                        }, enabled = !busy
                    ) { Text(if (busy) "Подключаем…" else "Включить тестовые уведомления") }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (notice.isNotBlank()) Text(notice, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
