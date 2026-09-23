package app.lumo

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Serializes in-process push POST/DELETE requests across UI and background
 * refresh. Consent is revoked locally BEFORE waiting for this gate.
 */
internal object PushOperationGate {
    val mutex = Mutex()
}

private suspend fun currentFirebaseToken(): String = suspendCancellableCoroutine { continuation ->
    FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
        if (continuation.isActive) {
            if (task.isSuccessful && !task.result.isNullOrBlank()) {
                continuation.resume(task.result)
            } else {
                continuation.resumeWithException(IllegalStateException("FCM token not available"))
            }
        }
    }
}

internal suspend fun deleteFirebaseToken() {
    suspendCancellableCoroutine<Unit> { continuation ->
        FirebaseMessaging.getInstance().deleteToken().addOnCompleteListener { task ->
            if (continuation.isActive) {
                if (task.isSuccessful) continuation.resume(Unit)
                else continuation.resumeWithException(IllegalStateException("FCM token deletion failed"))
            }
        }
    }
}

/**
 * Debug-only worker; no authentication credentials are persisted in job data.
 * Always reads the currently signed-in session from the app's existing private
 * preferences immediately before an authorized server operation.
 */
internal class LumoPushSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (!BuildConfig.LUMO_FCM_CONFIGURED) return Result.success()
        return PushOperationGate.mutex.withLock {
            val context = applicationContext
            val pending = PushOptState.pendingForCurrentSession(context)
            val enabled = PushOptState.activeAccount(context)
            when (PushSyncPolicy.choose(
                pendingRevoke = pending != null,
                consented = enabled != null,
                osPermissionGranted = PushOptState.permissionGranted(context)
            )) {
                PushSyncAction.REVOKE -> revoke(requireNotNull(pending))
                PushSyncAction.REGISTER -> refresh(requireNotNull(enabled))
                PushSyncAction.NONE -> Result.success()
            }
        }
    }

    private suspend fun revoke(account: Pair<String, String>): Result {
        val context = applicationContext
        return try {
            // Disable any spontaneous token refresh during an offline revoke.
            FirebaseMessaging.getInstance().isAutoInitEnabled = false
            withContext(Dispatchers.IO) { LumoPushApi.revoke(account.second) }
            // Remote deletion is authoritative. Token deletion is best-effort.
            runCatching { deleteFirebaseToken() }
            if (PushOptState.revokePending(context, account.first, account.second)) {
                PushOptState.clear(context)
            }
            Result.success()
        } catch (expired: SessionExpiredException) {
            // An expired/revoked session has no authorized device registration.
            if (PushOptState.revokePending(context, account.first, account.second)) {
                PushOptState.clear(context)
            }
            Result.success()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            // Keep local notifications disabled; WorkManager retries on network.
            Result.retry()
        }
    }

    private suspend fun refresh(account: Pair<String, String>): Result {
        val context = applicationContext
        return try {
            val latestToken = currentFirebaseToken()
            if (PushOptState.activeAccount(context) != account ||
                !PushOptState.permissionGranted(context)) {
                return if (PushOptState.pendingForCurrentSession(context) != null)
                    Result.retry() else Result.success()
            }
            withContext(Dispatchers.IO) {
                LumoPushApi.register(account.second, latestToken)
            }
            // Sign-out / opt-out may have happened during the HTTP request.
            // Never leave a newly registered token behind when that happens.
            if (PushOptState.activeAccount(context) != account ||
                !PushOptState.permissionGranted(context)) {
                withContext(Dispatchers.IO) { LumoPushApi.revoke(account.second) }
                return if (PushOptState.pendingForCurrentSession(context) != null)
                    Result.retry() else Result.success()
            }
            Result.success()
        } catch (expired: SessionExpiredException) {
            // The normal login screen will handle session restoration.
            if (PushOptState.activeAccount(context) == account) {
                PushOptState.clear(context)
            }
            Result.success()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val UNIQUE_WORK = "lumo_debug_push_reconciliation"

        fun schedule(context: Context) {
            if (!BuildConfig.LUMO_FCM_CONFIGURED) return
            val app = context.applicationContext
            if (PushOptState.pendingForCurrentSession(app) == null &&
                PushOptState.activeAccount(app) == null) return
            val request = OneTimeWorkRequestBuilder<LumoPushSyncWorker>()
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            // APPEND_OR_REPLACE makes a newly requested revoke run AFTER any
            // token registration already in progress; it cannot be dropped
            // merely because a previous worker has not yet finished.
            WorkManager.getInstance(app).enqueueUniqueWork(
                UNIQUE_WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, request
            )
        }
    }
}
