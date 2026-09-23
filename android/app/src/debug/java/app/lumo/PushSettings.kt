package app.lumo

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
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
import androidx.lifecycle.LifecycleOwner
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.withLock
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
    var revokePending by remember(session, me.id) {
        mutableStateOf(PushOptState.revokePending(context, me.id, session))
    }

    // Settings changes occur while another Android activity is foreground.
    // Synchronize the Profile UI after returning, without silently opting in.
    val lifecycleOwner = context as? LifecycleOwner
    DisposableEffect(lifecycleOwner, session, me.id) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                PushLifecycle.onAppResume(context)
                enabled = PushOptState.consented(context, me.id, session)
                revokePending = PushOptState.revokePending(context, me.id, session)
                if (revokePending) {
                    notice = "Уведомления выключены настройками Android. " +
                        "Удаление регистрации на сервере будет повторено при наличии сети."
                }
            }
        }
        lifecycleOwner?.lifecycle?.addObserver(observer)
        onDispose { lifecycleOwner?.lifecycle?.removeObserver(observer) }
    }

    fun openAndroidNotificationSettings() {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        context.startActivity(intent)
    }
    fun retryServerRevoke() {
        if (busy || !revokePending) return
        busy = true
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    PushOperationGate.mutex.withLock {
                        val messaging = FirebaseMessaging.getInstance()
                        messaging.isAutoInitEnabled = false
                        LumoPushApi.revoke(session)
                        // Complete token deletion before allowing a new opt-in.
                        runCatching { deleteFirebaseToken() }
                    }
                }
            }
            result.onSuccess {
                if (PushOptState.revokePending(context, me.id, session)) {
                    PushOptState.clear(context)
                }
                revokePending = false
                notice = "Уведомления отключены."
            }.onFailure {
                if (PushOptState.revokePending(context, me.id, session)) {
                    LumoPushSyncWorker.schedule(context)
                }
                notice = "Уведомления выключены на устройстве. " +
                    "Android автоматически повторит отключение на сервере при наличии сети."
            }
            busy = false
        }
    }

    LaunchedEffect(session, me.id) {
        if (PushOptState.revokePending(context, me.id, session)) {
            LumoPushSyncWorker.schedule(context)
        }
    }

    // WorkManager may finish while the Profile screen remains on screen:
    // never leave a stale "pending" warning after confirmed server deletion.
    LaunchedEffect(session, me.id, revokePending) {
        while (revokePending) {
            delay(4_000)
            if (!PushOptState.revokePending(context, me.id, session)) {
                revokePending = false
                notice = "Регистрация уведомлений на сервере удалена."
            }
        }
    }

    val permission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        when {
            granted && PushOptState.permissionGranted(context) -> requestEnable++
            granted -> notice = "Android разрешил запрос, но уведомления приложения " +
                "или канала Lumo по-прежнему выключены. Откройте настройки Android."
            else -> notice = "Уведомления не включены: разрешение не предоставлено. " +
                "При необходимости откройте настройки Android."
        }
    }

    LaunchedEffect(requestEnable) {
        if (requestEnable == 0 || !BuildConfig.LUMO_FCM_CONFIGURED || busy ||
            enabled || revokePending) return@LaunchedEffect
        busy = true
        notice = ""
        val result = runCatching {
            withContext(Dispatchers.IO) {
                PushOperationGate.mutex.withLock {
                    // This lock prevents an older token refresh from outliving
                    // the user's explicit disable / logout request.
                    check(!PushOptState.revokePending(context, me.id, session))
                    val messaging = FirebaseMessaging.getInstance()
                    messaging.isAutoInitEnabled = true
                    val fcmToken = getFcmToken()
                    check(PushOptState.permissionGranted(context))
                    check(context.getSharedPreferences("lumo_session", Context.MODE_PRIVATE)
                        .getString("token", null) == session)
                    LumoPushApi.register(session, fcmToken)
                    if (context.getSharedPreferences("lumo_session", Context.MODE_PRIVATE)
                        .getString("token", null) != session ||
                        !PushOptState.permissionGranted(context)) {
                        // A just-registered token must not remain with an old
                        // account after a sign-out or permission revocation.
                        LumoPushApi.revoke(session)
                        throw IllegalStateException("Session or permission changed")
                    }
                    PushOptState.enable(context, me.id, session)
                    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    manager.createNotificationChannel(
                        NotificationChannel("lumo_messages", "Сообщения Lumo",
                            NotificationManager.IMPORTANCE_DEFAULT)
                    )
                }
            }
        }
        result.onSuccess {
            enabled = true
            revokePending = false
            notice = "Тестовые уведомления включены только для текущего аккаунта."
            LumoPushSyncWorker.schedule(context)
        }.onFailure { error ->
            // Do not lose the server revoke even if navigation cancels Compose
            // between accepting a token and persisting the local consent.
            withContext(NonCancellable + Dispatchers.IO) {
                val stillThisSession = context.getSharedPreferences(
                    "lumo_session", Context.MODE_PRIVATE
                ).getString("token", null) == session
                if (stillThisSession) {
                    PushOptState.disableLocally(context, me.id, session)
                    runCatching { FirebaseMessaging.getInstance().isAutoInitEnabled = false }
                }
                val remotelyRevoked = runCatching {
                    PushOperationGate.mutex.withLock {
                        LumoPushApi.revoke(session)
                        if (stillThisSession) runCatching { deleteFirebaseToken() }
                    }
                }
                if (stillThisSession && PushOptState.revokePending(context, me.id, session)) {
                    if (remotelyRevoked.isSuccess ||
                        remotelyRevoked.exceptionOrNull() is SessionExpiredException) {
                        PushOptState.clear(context)
                    } else {
                        LumoPushSyncWorker.schedule(context)
                    }
                }
            }
            enabled = false
            revokePending = PushOptState.revokePending(context, me.id, session)
            notice = if (revokePending)
                "На телефоне выключено. Отключение на сервере повторится при подключении."
            else "Не удалось включить уведомления. Проверьте Firebase и сервер."
            if (error is CancellationException) throw error
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
                if (revokePending) {
                    Text(
                        "На этом устройстве выключено. Android повторит удаление " +
                            "регистрации на сервере автоматически при наличии сети.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Button(onClick = { retryServerRevoke() }, enabled = !busy) {
                        Text(if (busy) "Повторяем…" else "Повторить отключение")
                    }
                } else if (enabled) {
                    Text(if (PushOptState.permissionGranted(context)) "Включено" else
                        "Настройки Android запрещают уведомления — при возвращении " +
                            "регистрация будет отключена")
                    Button(
                        onClick = {
                            if (busy) return@Button
                            // Local permission takes precedence over network success.
                            // Data-only FCM is always filtered through this local flag.
                            PushOptState.disableLocally(context, me.id, session)
                            runCatching { FirebaseMessaging.getInstance().isAutoInitEnabled = false }
                            enabled = false
                            revokePending = true
                            notice = "На устройстве выключено. Отзываем регистрацию на сервере…"
                            retryServerRevoke()
                        }, enabled = !busy
                    ) { Text(if (busy) "Отключаем…" else "Отключить уведомления") }
                } else {
                    Button(
                        onClick = {
                            if (Build.VERSION.SDK_INT >= 33 &&
                                !PushOptState.runtimePermissionGranted(context)) {
                                permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else if (!PushOptState.permissionGranted(context)) {
                                openAndroidNotificationSettings()
                                notice = "Разрешите уведомления приложения и канала Lumo " +
                                    "в настройках Android, затем включите их снова."
                            } else {
                                requestEnable++
                            }
                        }, enabled = !busy
                    ) { Text(if (busy) "Подключаем…" else "Включить тестовые уведомления") }
                }
                if (!PushOptState.permissionGranted(context)) {
                    TextButton(onClick = { openAndroidNotificationSettings() }, enabled = !busy) {
                        Text("Открыть настройки уведомлений Android")
                    }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (notice.isNotBlank()) Text(notice, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
