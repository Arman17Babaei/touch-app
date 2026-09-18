package app.touch.communication

import android.content.Context
import app.touch.core.AndroidTouchPlayer
import app.touch.core.Touch
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume

class TouchCommunication private constructor(private val context: Context) {
    internal val settingsStore = SettingsStore(context)
    internal val database = TouchDatabase.get(context)
    internal val dao = database.touchDao()
    internal val api = TouchApiClient()
    internal val live = LiveTouchCoordinator(context, settingsStore)
    internal val platform: String = if (context.packageManager.hasSystemFeature("android.hardware.type.watch")) "watch" else "phone"

    val settings: Flow<CommunicationSettings> = settingsStore.settings
    val inbox: Flow<List<InboxTouch>> = dao.observeInbox().map { items -> items.map(InboxTouchEntity::toModel) }
    val outbox: Flow<List<OutboxTouch>> = dao.observeOutbox().map { items -> items.map(OutboxTouchEntity::toModel) }
    val liveStatus = live.status

    fun startLive() = live.start()
    fun stopLive() = live.stop()
    fun beginLiveTouch(samplePeriodMs: Int) = live.beginLocalStream(samplePeriodMs)
    fun streamSample(amplitude: Int) = live.sample(amplitude)
    fun endLiveTouch() = live.endLocalStream()

    suspend fun initialize() {
        settingsStore.ensureInstallationId()
        refreshFcmToken()
        CommunicationWork.enqueueRegistration(context)
        CommunicationWork.enqueueSync(context)
        CommunicationWork.enqueueOutbox(context)
    }

    suspend fun configure(backendUrl: String, username: String, peerUsername: String) {
        require(backendUrl.startsWith("http://") || backendUrl.startsWith("https://"))
        require(username.matches(HANDLE_PATTERN))
        require(peerUsername.matches(HANDLE_PATTERN))
        settingsStore.ensureInstallationId()
        settingsStore.saveConnection(backendUrl, username, peerUsername)
        refreshFcmToken()
        CommunicationWork.enqueueRegistration(context)
        CommunicationWork.enqueueSync(context)
        CommunicationWork.enqueueOutbox(context)
    }

    suspend fun send(touch: Touch): String = enqueue(touch, UUID.randomUUID().toString())

    internal suspend fun enqueue(touch: Touch, id: String): String {
        require(!touch.isSilent && touch.amplitudes.isNotEmpty())
        val current = settingsStore.current()
        require(current.isConfigured) { "Communication setup is incomplete" }
        dao.insertOutbox(
            OutboxTouchEntity(
                clientMessageId = id,
                recipientUsername = current.peerUsername,
                samplePeriodMillis = touch.samplePeriodMillis,
                amplitudes = touch.toBytes(),
                createdAt = System.currentTimeMillis(),
            ),
        )
        CommunicationWork.enqueueOutbox(context)
        return id
    }

    fun onForeground() {
        CommunicationWork.enqueueRegistration(context)
        CommunicationWork.enqueueSync(context)
        CommunicationWork.enqueueOutbox(context)
    }

    fun syncInbox() {
        CommunicationWork.enqueueSync(context)
    }

    fun setInteractionBusy(busy: Boolean) {
        PlaybackCoordinator.interactionBusy.set(busy)
        if (!busy) CommunicationWork.enqueueAutoPlay(context)
    }

    suspend fun play(item: InboxTouch): Boolean = PlaybackCoordinator.playOne(context, this, item.id, item.touch)

    internal suspend fun refreshFcmToken() {
        if (FirebaseApp.getApps(context).isEmpty()) return
        runCatching {
            val token = FirebaseMessaging.getInstance().token.awaitResult()
            settingsStore.saveFcmToken(token)
        }
    }

    companion object {
        private val HANDLE_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9_.-]{2,31}$")
        @Volatile private var instance: TouchCommunication? = null

        fun get(context: Context): TouchCommunication = instance ?: synchronized(this) {
            instance ?: TouchCommunication(context.applicationContext).also { instance = it }
        }
    }
}

private suspend fun <T> com.google.android.gms.tasks.Task<T>.awaitResult(): T =
    kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
        addOnCompleteListener { task ->
            if (task.isSuccessful) continuation.resume(task.result)
            else continuation.cancel(task.exception ?: IllegalStateException("Firebase task failed"))
        }
    }

internal object PlaybackCoordinator {
    val interactionBusy = AtomicBoolean(false)
    private val mutex = Mutex()

    suspend fun playFresh(context: Context, communication: TouchCommunication) {
        if (interactionBusy.get()) return
        mutex.withLock {
            if (interactionBusy.get()) return
            val cutoff = System.currentTimeMillis() - 5 * 60_000L
            communication.dao.freshUnplayed(cutoff).sortedWith(compareBy(InboxTouchEntity::createdAt, InboxTouchEntity::id)).forEach { item ->
                if (interactionBusy.get()) return
                playOne(context, communication, item.id, item.amplitudes.toTouch(item.samplePeriodMillis))
            }
        }
    }

    suspend fun playOne(context: Context, communication: TouchCommunication, id: String, touch: Touch): Boolean {
        if (!interactionBusy.compareAndSet(false, true)) return false
        return try {
            val played = AndroidTouchPlayer(context.applicationContext).play(touch)
            if (played) {
                delay(touch.durationMillis + 100)
                val playedAt = System.currentTimeMillis()
                communication.dao.markPlayed(id, playedAt)
                runCatching { communication.api.ack(communication.settingsStore.current(), id, "played") }
            }
            played
        } finally {
            interactionBusy.set(false)
        }
    }
}
