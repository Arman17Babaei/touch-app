package app.touch.communication

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.Constraints
import java.io.IOException

internal object CommunicationWork {
    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    // These are state-reconciliation jobs backed by Room/server idempotency. Replacing an old
    // retry resets WorkManager's exponential backoff as soon as connectivity returns. KEEP (or
    // appending behind an ENQUEUED retry) can otherwise make foreground sync wait for hours.
    fun enqueueRegistration(context: Context) = enqueue<RegistrationWorker>(context, "touch-registration", ExistingWorkPolicy.REPLACE)
    fun enqueueOutbox(context: Context) = enqueue<OutboxWorker>(context, "touch-outbox", ExistingWorkPolicy.REPLACE)
    fun enqueueSync(context: Context) = enqueue<InboxSyncWorker>(context, "touch-inbox-sync", ExistingWorkPolicy.REPLACE)
    fun enqueueAutoPlay(context: Context) = enqueue<AutoPlayWorker>(context, "touch-auto-play", ExistingWorkPolicy.KEEP, requiresNetwork = false)

    private inline fun <reified T : ListenableWorker> enqueue(
        context: Context,
        name: String,
        policy: ExistingWorkPolicy,
        requiresNetwork: Boolean = true,
    ) {
        val builder = OneTimeWorkRequestBuilder<T>()
        if (requiresNetwork) builder.setConstraints(network)
        WorkManager.getInstance(context).enqueueUniqueWork(name, policy, builder.build())
    }
}

internal class RegistrationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val communication = TouchCommunication.get(applicationContext)
        return workerResult {
            communication.refreshFcmToken()
            val settings = communication.settingsStore.current()
            if (settings.isConfigured) communication.api.register(settings, communication.platform)
        }
    }
}

internal class OutboxWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val communication = TouchCommunication.get(applicationContext)
        val settings = communication.settingsStore.current()
        if (!settings.isConfigured) return Result.success()
        return try {
            communication.api.register(settings, communication.platform)
            communication.dao.pendingOutbox().forEach { item ->
                communication.dao.updateOutbox(item.clientMessageId, OutboxStatus.SENDING.name)
                try {
                    val accepted = communication.api.send(settings, item)
                    communication.dao.updateOutboxSent(item.clientMessageId, OutboxStatus.SENT.name, accepted.touchId)
                } catch (error: ApiException) {
                    if (shouldRetryHttp(error.statusCode)) {
                        communication.dao.updateOutbox(item.clientMessageId, OutboxStatus.QUEUED.name, error.message)
                        return Result.retry()
                    }
                    communication.dao.updateOutbox(item.clientMessageId, OutboxStatus.FAILED.name, error.message)
                }
            }
            communication.dao.trimOutbox()
            Result.success()
        } catch (error: ApiException) {
            val retry = shouldRetryHttp(error.statusCode)
            communication.dao.pendingOutbox().forEach {
                communication.dao.updateOutbox(
                    it.clientMessageId,
                    if (retry) OutboxStatus.QUEUED.name else OutboxStatus.FAILED.name,
                    error.message,
                )
            }
            if (retry) Result.retry() else Result.failure()
        } catch (_: IOException) {
            communication.dao.pendingOutbox().forEach {
                communication.dao.updateOutbox(it.clientMessageId, OutboxStatus.QUEUED.name)
            }
            Result.retry()
        }
    }
}

internal class InboxSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val communication = TouchCommunication.get(applicationContext)
        val settings = communication.settingsStore.current()
        if (!settings.isConfigured) return Result.success()
        return try {
            var inserted = 0
            val pending = communication.api.pending(settings)
            pending.forEach { remote ->
                val result = communication.dao.insertInbox(
                    InboxTouchEntity(
                        id = remote.touchId,
                        clientMessageId = remote.clientMessageId,
                        senderUsername = remote.senderUsername,
                        samplePeriodMillis = remote.samplePeriodMs,
                        amplitudes = remote.amplitudes.map { it.toByte() }.toByteArray(),
                        createdAt = remote.createdAtMs,
                        receivedAt = System.currentTimeMillis(),
                    ),
                )
                if (result != -1L) inserted++
            }
            communication.dao.trimInbox()
            if (inserted > 0) TouchNotifications.show(applicationContext, inserted)
            PlaybackCoordinator.playFresh(applicationContext, communication)
            pending.forEach { remote -> communication.api.ack(settings, remote.touchId, "persisted") }
            Result.success()
        } catch (_: IOException) {
            Result.retry()
        }
    }
}

internal class AutoPlayWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        PlaybackCoordinator.playFresh(applicationContext, TouchCommunication.get(applicationContext))
        return Result.success()
    }
}

private suspend fun CoroutineWorker.workerResult(block: suspend () -> Unit): ListenableWorker.Result = try {
    block()
    ListenableWorker.Result.success()
} catch (error: ApiException) {
    if (shouldRetryHttp(error.statusCode)) ListenableWorker.Result.retry() else ListenableWorker.Result.failure()
} catch (_: IOException) {
    ListenableWorker.Result.retry()
}

internal fun shouldRetryHttp(statusCode: Int): Boolean =
    statusCode == 408 || statusCode == 429 || statusCode >= 500
