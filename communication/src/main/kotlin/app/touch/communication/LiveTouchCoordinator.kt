package app.touch.communication

import android.content.Context
import app.touch.core.AndroidTouchPlayer
import app.touch.core.Touch
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject

enum class LiveStatus { OFF, CONNECTING, RINGING, INCOMING, CONNECTED, RECONNECTING, ENDED, ERROR }

data class LiveState(
    val status: LiveStatus = LiveStatus.OFF,
    val callId: String? = null,
    val peerUsername: String = "",
    val reason: String? = null,
    val connectedAtMs: Long? = null,
    val generation: Int = 0,
    val notificationStatus: String = "",
)

data class LiveDiagnostics(
    val bufferedMillis: Int = 0,
    val underruns: Long = 0,
    val outOfOrderBatches: Long = 0,
    val playbackRestarts: Long = 0,
    val sentBatches: Long = 0,
    val receivedBatches: Long = 0,
    val reconnectAttempts: Long = 0,
)

/** Foreground-only call transport. Live samples are never persisted in the durable inbox. */
internal class LiveTouchCoordinator(private val context: Context, private val settings: SettingsStore) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient()
    private val player = LivePlaybackBuffer(context.applicationContext, scope)
    private val audio = LiveAudioEngine(context.applicationContext, scope) { event -> socket?.send(event.toString()) }
    private val amplitude = AtomicInteger(0)
    private val _state = MutableStateFlow(LiveState())
    val state: StateFlow<LiveState> = _state
    private val _status = MutableStateFlow(LiveStatus.OFF)
    val status: StateFlow<LiveStatus> = _status
    val diagnostics: StateFlow<LiveDiagnostics> = player.diagnostics
    val audioState: StateFlow<LiveAudioState> = audio.state

    @Volatile var lastError: String? = null
        private set
    @Volatile var receivedBatchCount: Long = 0
        private set
    @Volatile var lastPlaybackAtMs: Long? = null
        private set

    private var socket: WebSocket? = null
    private var socketOpen = false
    private var intentionalClose = false
    private var outgoingCall = false
    private var pendingAccept = false
    private var reconnectDeadlineMs = 0L
    private var reconnectJob: Job? = null
    private var samplerJob: Job? = null
    private var healthJob:Job?=null
    private var hangupJob:Job?=null
    private var generation=0
    private var sentBatches=0L
    private var reconnectAttempts=0L
    private var peerUsername = ""
    private var callId: String? = null
    private var streamId: String? = null
    private var nextIndex = 0
    private var remoteStream: String? = null
    private var expectedIndex = 0
    private var samplePeriodMs = SAMPLE_PERIOD_MS
    private val outgoingLock = Any()
    private val outgoing = ArrayList<Int>(BATCH_SAMPLES)

    fun startCall() = scope.launch {
        val current = settings.current()
        if (!current.isConfigured) {
            update(LiveStatus.ERROR, reason = "Communication setup is incomplete")
            return@launch
        }
        resetTransport()
        callId = UUID.randomUUID().toString()
        peerUsername = current.peerUsername
        outgoingCall = true
        intentionalClose = false
        update(LiveStatus.CONNECTING)
        openSocket(current)
    }

    fun prepareIncoming(incomingCallId: String, callerUsername: String) = scope.launch {
        val current = settings.current()
        if (!current.isConfigured) {
            update(LiveStatus.ERROR, incomingCallId, callerUsername, "Communication setup is incomplete")
            return@launch
        }
        resetTransport()
        callId = incomingCallId
        peerUsername = callerUsername
        outgoingCall = false
        intentionalClose = false
        update(LiveStatus.INCOMING)
        openSocket(current)
    }

    fun accept() {
        val id = callId ?: return
        pendingAccept = true
        if (socketOpen) {
            sendControl("accept", id)
            pendingAccept = false
        } else reconnect(resume = false)
    }

    fun decline() {
        callId?.let { sendControl("decline", it) }
        closeLocal("declined", LiveStatus.ENDED)
    }

    fun hangUp() {
        val id = callId ?: return
        // OkHttp drops frames queued immediately before close on some devices. Give the
        // control frame a short turn on the writer before closing the socket locally.
        scope.launch {
            // Stop producers first so no media frames can be queued behind hangup and
            // rejected after the server has removed the call.
            stopStreaming()
            val sent = socketOpen && (socket?.send(JSONObject().put("type", "hangup").put("callId", id).toString()) == true)
            if(!sent){closeLocal("hangup_send_failed",LiveStatus.ERROR);return@launch}
            hangupJob?.cancel();hangupJob=scope.launch{delay(HANGUP_ACK_MILLIS);if(_state.value.status !in setOf(LiveStatus.ENDED,LiveStatus.ERROR))closeLocal("hangup_timeout",LiveStatus.ERROR)}
        }
    }

    fun setAmplitude(value: Int) {
        amplitude.set(value.coerceIn(0, 255))
    }
    fun setMicrophoneEnabled(enabled: Boolean) = audio.setMicrophoneEnabled(enabled)
    fun setAudioOutputEnabled(allowSpeaker: Boolean) = audio.setOutputEnabled(allowSpeaker)
    fun onAudioRouteChanged() = audio.onRouteChanged()

    private suspend fun openSocket(current: CommunicationSettings) {
        val url = current.backendUrl.replace(Regex("^http"), "ws") + "/v1/live"
        CommunicationLog.info("WebSocket connecting url=$url installation=${current.installationId.take(8)}")
        socket = client.newWebSocket(
            Request.Builder().url(url).header("X-Installation-ID", current.installationId).build(),
            listener,
        )
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (socket !== webSocket) return
            socketOpen = true
            lastError = null
            CommunicationLog.info("WebSocket connected status=${response.code}")
            val id = callId ?: return
            when {
                _state.value.status == LiveStatus.RECONNECTING -> sendControl("resume", id)
                pendingAccept -> {
                    sendControl("accept", id)
                    pendingAccept = false
                }
                outgoingCall -> socket?.send(
                    JSONObject().put("type", "call").put("callId", id)
                        .put("recipientUsername", peerUsername).toString(),
                )
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (socket !== webSocket) return
            val event = runCatching { JSONObject(text) }.getOrNull() ?: return
            when (event.optString("type")) {
                "ringing" -> update(LiveStatus.RINGING, event.optString("callId"), event.optString("peerUsername"), notificationStatus = event.optString("notificationStatus"))
                "incoming" -> {
                    callId = event.optString("callId")
                    peerUsername = event.optString("callerUsername")
                    outgoingCall = false
                    update(LiveStatus.INCOMING)
                }
                "connected" -> onConnected(event)
                "reconnecting" -> {
                    stopStreaming()
                    update(LiveStatus.RECONNECTING, event.optString("callId"), event.optString("peerUsername"))
                }
                "ended" -> closeLocal(event.optString("reason", "ended"), LiveStatus.ENDED)
                "start" -> {
                    remoteStream = event.optString("streamId")
                    samplePeriodMs = event.optInt("samplePeriodMs", SAMPLE_PERIOD_MS)
                    expectedIndex = 0
                    player.reset(samplePeriodMs)
                }
                "samples" -> receive(event)
                "end" -> {
                    remoteStream = null
                    player.reset(samplePeriodMs)
                }
                "audioStart", "audioFrame", "audioEnd" -> audio.receive(event)
                "peerUnavailable" -> update(LiveStatus.RECONNECTING, reason = "Peer unavailable")
                "error" -> {
                    lastError = event.optString("code", "Live call error")
                    CommunicationLog.warn("Live event error code=$lastError")
                    closeLocal(liveErrorMessage(lastError), LiveStatus.ERROR)
                }
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (socket !== webSocket) return
            socketOpen = false
            socket = null
            if (intentionalClose) return
            lastError = "${response?.code ?: "network"}: ${t.message}"
            CommunicationLog.warn("WebSocket failure status=${response?.code ?: "none"} error=${t.message}", t)
            handleUnexpectedDisconnect()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (socket !== webSocket) return
            socketOpen = false
            socket = null
            CommunicationLog.info("WebSocket closed code=$code reason=$reason")
            if (!intentionalClose) handleUnexpectedDisconnect()
        }
    }

    private fun onConnected(event: JSONObject) {
        callId = event.optString("callId")
        peerUsername = event.optString("peerUsername", peerUsername)
        reconnectDeadlineMs = 0
        generation=event.optInt("generation",generation+1)
        update(LiveStatus.CONNECTED, connectedAtMs = System.currentTimeMillis())
        startStreaming()
        callId?.let(audio::start)
        healthJob?.cancel();healthJob=scope.launch{while(_state.value.status==LiveStatus.CONNECTED){delay(5_000);val a=audio.state.value;callId?.let{id->socket?.send(JSONObject().put("type","clientHealth").put("callId",id).put("diagnostics",JSONObject().put("sentBatches",sentBatches).put("receivedBatches",receivedBatchCount).put("hapticBufferedMs",diagnostics.value.bufferedMillis).put("hapticUnderruns",diagnostics.value.underruns).put("audioEncoded",a.encodedFrames).put("audioDecoded",a.decodedFrames).put("audioDropped",a.droppedFrames).put("audioDecoderErrors",a.decoderErrors).put("microphoneEnabled",a.microphoneEnabled).put("outputEnabled",a.outputEnabled)).toString())}}}
    }

    private fun startStreaming() {
        stopStreaming()
        synchronized(outgoingLock) {
            streamId = UUID.randomUUID().toString()
            nextIndex = 0
            outgoing.clear()
        }
        socket?.send(
            JSONObject().put("type", "start").put("callId",callId).put("streamId", streamId)
                .put("recipientUsername", peerUsername).put("samplePeriodMs", SAMPLE_PERIOD_MS).toString(),
        )
        samplerJob = scope.launch {
            while (_state.value.status == LiveStatus.CONNECTED) {
                synchronized(outgoingLock) {
                    outgoing += amplitude.get()
                    if (outgoing.size >= BATCH_SAMPLES) flush()
                }
                delay(SAMPLE_PERIOD_MS.toLong())
            }
        }
    }

    private fun stopStreaming(notifyPeer: Boolean = true) {
        samplerJob?.cancel()
        samplerJob = null
        amplitude.set(0)
        if (notifyPeer) flush()
        synchronized(outgoingLock) {
            if (notifyPeer) streamId?.let { socket?.send(JSONObject().put("type", "end").put("callId",callId).put("streamId", it).toString()) }
            streamId = null
            outgoing.clear()
        }
        remoteStream = null
        player.reset(samplePeriodMs)
        audio.stop(notifyPeer)
    }

    private fun flush() {
        synchronized(outgoingLock) {
            val id = streamId ?: return
            if (outgoing.isEmpty()) return
            val values = JSONArray(outgoing)
            socket?.send(
                JSONObject().put("type", "samples").put("callId",callId).put("streamId", id)
                    .put("startIndex", nextIndex).put("amplitudes", values).toString(),
            )
            nextIndex += outgoing.size
            sentBatches++
            outgoing.clear()
        }
    }

    private fun receive(event: JSONObject) {
        val values = event.optJSONArray("amplitudes") ?: return
        if (event.optString("streamId") != remoteStream) {
            player.outOfOrder()
            return
        }
        val startIndex = event.optInt("startIndex", -1)
        if (startIndex < expectedIndex) {
            // Late duplicates are never replayed.
            player.outOfOrder()
            return
        }
        if (startIndex > expectedIndex) {
            // The server queue is bounded, so a congested connection can lose a batch.
            // Resume at the next sequenced batch instead of making the remainder of the
            // live haptic stream permanently unplayable.
            player.gap(startIndex - expectedIndex)
            expectedIndex = startIndex
        }
        expectedIndex += values.length()
        val samples = List(values.length()) { values.getInt(it) }
        player.offer(samples)
        receivedBatchCount++
        lastPlaybackAtMs = System.currentTimeMillis()
    }

    private fun handleUnexpectedDisconnect() {
        // The transport is already gone, so media-end frames cannot be delivered.
        stopStreaming(notifyPeer = false)
        if (_state.value.status == LiveStatus.CONNECTED || _state.value.status == LiveStatus.RECONNECTING) {
            if (reconnectDeadlineMs == 0L) reconnectDeadlineMs = System.currentTimeMillis() + RECONNECT_MILLIS
            update(LiveStatus.RECONNECTING, reason = "Connection lost")
            reconnect(resume = true)
        } else {
            update(LiveStatus.ERROR, reason = lastError ?: "Connection lost")
        }
    }

    private fun reconnect(resume: Boolean) {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            if (reconnectDeadlineMs == 0L) reconnectDeadlineMs = System.currentTimeMillis() + RECONNECT_MILLIS
            while (!intentionalClose && System.currentTimeMillis() < reconnectDeadlineMs) {
                delay(1_000)
                if (socket == null) {
                    reconnectAttempts++
                    val current = settings.current()
                    if (!current.isConfigured) break
                    if (resume) update(LiveStatus.RECONNECTING)
                    openSocket(current)
                    return@launch
                }
            }
            if (!intentionalClose) closeLocal("connection_lost", LiveStatus.ENDED)
        }
    }

    private fun sendControl(type: String, id: String) {
        socket?.send(JSONObject().put("type", type).put("callId", id).toString())
    }

    private fun closeLocal(reason: String?, terminalStatus: LiveStatus) {
        intentionalClose = true
        reconnectJob?.cancel()
        reconnectJob = null
        healthJob?.cancel();healthJob=null;hangupJob?.cancel();hangupJob=null
        // The server removes the call before delivering its terminal acknowledgement;
        // do not emit media-end frames after that acknowledgement.
        stopStreaming(notifyPeer = false)
        socket?.close(1000, reason ?: "stopped")
        socket = null
        socketOpen = false
        update(terminalStatus, reason = reason)
    }

    private fun resetTransport() {
        intentionalClose = true
        reconnectJob?.cancel()
        stopStreaming()
        socket?.close(1000, "new call")
        socket = null
        socketOpen = false
        reconnectDeadlineMs = 0
        lastError = null
        callId = null
        pendingAccept = false
    }

    private fun update(
        status: LiveStatus,
        id: String? = callId,
        peer: String = peerUsername,
        reason: String? = null,
        connectedAtMs: Long? = if (status == LiveStatus.CONNECTED) _state.value.connectedAtMs else null,
        notificationStatus: String = _state.value.notificationStatus,
    ) {
        if (id != null) callId = id
        if (peer.isNotBlank()) peerUsername = peer
        _state.value = LiveState(status, callId, peerUsername, reason, connectedAtMs,generation,notificationStatus)
        _status.value = status
        TouchCommunication.get(context).recordDiagnostic(
            if(status==LiveStatus.ERROR)"error" else "info","call","state_${status.name.lowercase()}",
            reason.orEmpty(),callId,mapOf("peer" to peerUsername.take(32),"generation" to generation,"socketOpen" to socketOpen),
        )
    }

    companion object {
        const val SAMPLE_PERIOD_MS = 10
        const val BATCH_SAMPLES = 10
        const val RECONNECT_MILLIS = 30_000L
        const val HANGUP_ACK_MILLIS = 1_000L
    }
}

internal fun liveErrorMessage(code:String?):String = when(code){
    "RECIPIENT_UNAVAILABLE"->"Recipient is unavailable (RECIPIENT_UNAVAILABLE)"
    "CALL_BUSY"->"One of the contacts is already in a call (CALL_BUSY)"
    "CALL_UNAVAILABLE"->"The call expired or is unavailable (CALL_UNAVAILABLE)"
    "INVALID_AUDIO"->"The live audio stream was rejected (INVALID_AUDIO)"
    "INVALID_SAMPLES"->"The touch stream was rejected (INVALID_SAMPLES)"
    "INVALID_EVENT"->"The server rejected a call event (INVALID_EVENT)"
    null,""->"Unknown live call error"
    else->"Live call failed ($code)"
}

private class LivePlaybackBuffer(context: Context, private val scope: CoroutineScope) {
    private val player = AndroidTouchPlayer(context)
    private val lock = Any()
    private val queue = ArrayDeque<Int>()
    private var samplePeriodMs = 10
    private var playbackJob: Job? = null
    private var underruns = 0L
    private var outOfOrder = 0L
    private var restarts = 0L
    private val _diagnostics = MutableStateFlow(LiveDiagnostics())
    val diagnostics: StateFlow<LiveDiagnostics> = _diagnostics

    fun reset(periodMs: Int) {
        val job = synchronized(lock) {
            playbackJob.also {
                playbackJob = null
                queue.clear()
                samplePeriodMs = periodMs
            }
        }
        job?.cancel()
        player.cancel()
        publish()
    }

    fun offer(samples: List<Int>) {
        var newJob: Job? = null
        synchronized(lock) {
            queue.addAll(samples)
            if (playbackJob == null && queue.size * samplePeriodMs >= INITIAL_BUFFER_MILLIS) {
                restarts++
                newJob = scope.launch(start = CoroutineStart.LAZY) { playLoop() }
                playbackJob = newJob
            }
        }
        publish()
        newJob?.start()
    }

    fun outOfOrder() { synchronized(lock) { outOfOrder++ }; publish() }

    fun gap(@Suppress("UNUSED_PARAMETER") skippedSamples: Int) {
        synchronized(lock) { outOfOrder++ }
        publish()
    }

    private suspend fun playLoop() {
        while (true) {
            val chunk = synchronized(lock) {
                val playCount = PLAY_AHEAD_MILLIS / samplePeriodMs
                val advanceCount = ADVANCE_MILLIS / samplePeriodMs
                if (queue.size < playCount) null else {
                    val values = queue.take(playCount)
                    repeat(advanceCount) { queue.removeFirst() }
                    values
                }
            }
            if (chunk == null) {
                synchronized(lock) {
                    underruns++
                    playbackJob = null
                }
                publish()
                return
            }
            player.play(Touch(samplePeriodMs, chunk), cancelExisting = false)
            publish()
            delay(ADVANCE_MILLIS.toLong())
        }
    }

    private fun publish() {
        _diagnostics.value = synchronized(lock) {
            LiveDiagnostics(queue.size * samplePeriodMs, underruns, outOfOrder, restarts)
        }
    }

    companion object {
        const val INITIAL_BUFFER_MILLIS = 300
        const val PLAY_AHEAD_MILLIS = 300
        const val ADVANCE_MILLIS = 200
    }
}
