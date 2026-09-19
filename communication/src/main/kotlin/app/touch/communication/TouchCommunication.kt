package app.touch.communication

import android.content.Context
import app.touch.core.AndroidTouchPlayer
import app.touch.core.Touch
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume

class TouchCommunication private constructor(private val context: Context) {
    internal val settingsStore = SettingsStore(context)
    internal val database = TouchDatabase.get(context)
    internal val dao = database.touchDao()
    internal val api = TouchApiClient()
    internal val live = LiveTouchCoordinator(context, settingsStore)
    internal val diagnostics = DiagnosticReporter(dao)
    internal val platform: String = if (context.packageManager.hasSystemFeature("android.hardware.type.watch")) "watch" else "phone"

    val settings: Flow<CommunicationSettings> = settingsStore.settings
    val inbox: Flow<List<InboxTouch>> = dao.observeInbox().map { items -> items.map(InboxTouchEntity::toModel) }
    val outbox: Flow<List<OutboxTouch>> = dao.observeOutbox().map { items -> items.map(OutboxTouchEntity::toModel) }
    val liveStatus = live.status
	val liveState = live.state
    val liveAudioState = live.audioState
    val liveDiagnostics = live.diagnostics
    private val _contacts = MutableStateFlow<List<Contact>>(emptyList())
    val contacts: StateFlow<List<Contact>> = _contacts
    private val _installationStatus = MutableStateFlow<InstallationStatus?>(null)
    val installationStatus: StateFlow<InstallationStatus?> = _installationStatus

    fun startLive() = LiveCallService.startOutgoing(context)
    fun prepareIncomingLive(callId: String, callerUsername: String) = LiveCallService.startIncoming(context,callId,callerUsername,null)
    fun acceptLive() = LiveCallService.command(context,LiveCallService.ACTION_ACCEPT)
    fun declineLive() = LiveCallService.command(context,LiveCallService.ACTION_DECLINE)
    fun stopLive() = LiveCallService.command(context,LiveCallService.ACTION_HANGUP)
    fun setLiveAmplitude(amplitude: Int) = live.setAmplitude(amplitude)
    fun setLiveMicrophoneEnabled(enabled: Boolean) = live.setMicrophoneEnabled(enabled)
    fun setLiveAudioOutputEnabled(allowSpeaker: Boolean) = live.setAudioOutputEnabled(allowSpeaker)
    fun onAudioRouteChanged() = live.onAudioRouteChanged()
    fun onBackground() {
        // The foreground call service owns active calls across screen dimming and activity changes.
    }

    suspend fun initialize() {
        settingsStore.ensureInstallationId()
        refreshFcmToken()
        CommunicationWork.enqueueRegistration(context)
        CommunicationWork.enqueueSync(context)
        CommunicationWork.enqueueOutbox(context)
        CommunicationWork.enqueueDiagnostics(context)
        refreshDirectory()
    }

    suspend fun configure(backendUrl: String, username: String, peerUsername: String) {
        require(backendUrl.startsWith("http://") || backendUrl.startsWith("https://"))
        require(username.matches(HANDLE_PATTERN))
        require(peerUsername.isBlank() || peerUsername.matches(HANDLE_PATTERN))
        settingsStore.ensureInstallationId()
        settingsStore.saveConnection(backendUrl, username, peerUsername)
        refreshFcmToken()
        CommunicationWork.enqueueRegistration(context)
        CommunicationWork.enqueueSync(context)
        CommunicationWork.enqueueOutbox(context)
    }

    suspend fun send(touch: Touch, audio: AudioAttachment? = null): String = enqueue(touch, UUID.randomUUID().toString(), audio)

    internal suspend fun enqueue(touch: Touch, id: String, audio: AudioAttachment? = null): String {
        require(!touch.isSilent && touch.amplitudes.isNotEmpty())
        val current = settingsStore.current()
        require(current.isConfigured) { "Communication setup is incomplete" }
        require(current.peerUsername.matches(HANDLE_PATTERN)) { "Choose a contact first" }
        dao.insertOutbox(
            OutboxTouchEntity(
                clientMessageId = id,
                recipientUsername = current.peerUsername,
                samplePeriodMillis = touch.samplePeriodMillis,
                amplitudes = touch.toBytes(),
                createdAt = System.currentTimeMillis(),
                audioCodec = audio?.codec,
                audioSampleRateHz = audio?.sampleRateHz,
                audioChannelCount = audio?.channelCount,
                audioDurationMs = audio?.durationMs,
                audioData = audio?.data,
            ),
        )
        CommunicationWork.enqueueOutbox(context)
        return id
    }

    fun onForeground() {
        CommunicationWork.enqueueRegistration(context)
        CommunicationWork.enqueueSync(context)
        CommunicationWork.enqueueOutbox(context)
        CommunicationWork.enqueueDiagnostics(context)
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch { refreshDirectory() }
    }

    fun syncInbox() {
        CommunicationWork.enqueueSync(context)
    }

    fun setInteractionBusy(busy: Boolean) {
        PlaybackCoordinator.interactionBusy.set(busy)
        if (!busy) CommunicationWork.enqueueAutoPlay(context)
    }

    suspend fun play(item: InboxTouch): Boolean = PlaybackCoordinator.playOne(context, this, item.id, item.touch)
    suspend fun playAudio(item: InboxTouch, allowSpeaker: Boolean): Boolean = AudioPlaybackCoordinator.play(context, dao, item, allowSpeaker)
    suspend fun previewAudio(audio: AudioAttachment?, allowSpeaker: Boolean): Boolean = AudioPlaybackCoordinator.playAttachment(context, audio, allowSpeaker)
    fun stopAudio() = AudioPlaybackCoordinator.stop()

    suspend fun refreshDirectory() {
        val current=settingsStore.current(); if(!current.isConfigured)return
        runCatching { _contacts.value=api.contacts(current); _installationStatus.value=api.installationStatus(current) }
    }
    suspend fun selectContact(username:String) { val current=settingsStore.current(); settingsStore.saveConnection(current.backendUrl,current.username,username); refreshDirectory() }
    suspend fun addContact(username:String) { api.addContact(settingsStore.current(),username); selectContact(username) }
    suspend fun removeContact(username:String) { api.removeContact(settingsStore.current(),username); refreshDirectory() }
    suspend fun verifyNotifications(onUpdate:(PushTest)->Unit): PushTest {
        val current=settingsStore.current(); var result=api.startPushTest(current);onUpdate(result)
        repeat(15){ if(result.status!="pending")return result;delay(2_000);result=api.pushTest(current,result.testId);onUpdate(result) }
        return result
    }
    internal fun recordDiagnostic(severity:String,category:String,name:String,message:String="",callId:String?=null,attributes:Map<String,Any?> = emptyMap()) {
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch { diagnostics.record(severity,category,name,message,callId=callId,attributes=attributes);CommunicationWork.enqueueDiagnostics(context) }
    }

    suspend fun clearInbox() {
        PlaybackCoordinator.cancel()
        AudioPlaybackCoordinator.stop()
        dao.clearInbox()
        TouchNotifications.dismiss(context)
    }

    internal suspend fun refreshFcmToken() {
        if (FirebaseApp.getApps(context).isEmpty()) {
            CommunicationLog.warn("FCM unavailable: Firebase is not configured")
            return
        }
        runCatching {
            val token = FirebaseMessaging.getInstance().token.awaitResult()
            settingsStore.saveFcmToken(token)
            CommunicationLog.info("FCM registration token refreshed")
        }.onFailure { CommunicationLog.warn("FCM token refresh failed: ${it.message}", it) }
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
    private val generation = AtomicLong(0)
    private val activePlayer = AtomicReference<AndroidTouchPlayer?>(null)

    fun cancel() {
        generation.incrementAndGet()
        activePlayer.getAndSet(null)?.cancel()
        interactionBusy.set(false)
    }

    suspend fun playFresh(context: Context, communication: TouchCommunication) {
        if (interactionBusy.get()) return
        mutex.withLock {
            if (interactionBusy.get()) return
            val cutoff = System.currentTimeMillis() - 5 * 60_000L
            communication.dao.freshUnplayed(cutoff).sortedWith(compareBy(InboxTouchEntity::createdAt, InboxTouchEntity::id)).forEach { item ->
                if (interactionBusy.get()) return
                playOne(context, communication, item.id, item.amplitudes.toTouch(item.samplePeriodMillis))
				item.audioData?.let { audio -> AudioPlaybackCoordinator.play(context, communication.dao, item.toModel(), allowSpeaker = false) }
            }
        }
    }

    suspend fun playOne(context: Context, communication: TouchCommunication, id: String, touch: Touch): Boolean {
        if (!interactionBusy.compareAndSet(false, true)) return false
        val playbackGeneration = generation.get()
        val player = AndroidTouchPlayer(context.applicationContext)
        activePlayer.set(player)
        return try {
            val played = player.play(touch)
            if (played) {
                delay(touch.durationMillis + 100)
                if (generation.get() == playbackGeneration) {
                    val playedAt = System.currentTimeMillis()
                    communication.dao.markPlayed(id, playedAt)
                    runCatching { communication.api.ack(communication.settingsStore.current(), id, "played") }
                }
            }
            played
        } finally {
            activePlayer.compareAndSet(player, null)
            interactionBusy.set(false)
        }
    }
}
