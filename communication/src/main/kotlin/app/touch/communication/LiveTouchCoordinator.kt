package app.touch.communication

import android.content.Context
import app.touch.core.AndroidTouchPlayer
import app.touch.core.Touch
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

enum class LiveStatus { OFF, CONNECTING, LIVE, PEER_UNAVAILABLE, ERROR }

/** Foreground-only relay: buffered samples are intentionally never written to the durable inbox. */
internal class LiveTouchCoordinator(private val context: Context, private val settings: SettingsStore) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient()
    private val _status = MutableStateFlow(LiveStatus.OFF)
    val status: StateFlow<LiveStatus> = _status
    @Volatile var lastError: String? = null
    @Volatile var receivedBatchCount: Long = 0
        private set
    @Volatile var lastPlaybackAtMs: Long? = null
        private set
    private var socket: WebSocket? = null
    private var peerUsername = ""
    private var streamId: String? = null
    private var samplePeriodMs = 10
    private var nextIndex = 0
    private val outgoing = ArrayList<Int>(10)
    private var remoteStream: String? = null
    private var expectedIndex = 0

    fun start() = scope.launch {
        val current = settings.current(); if (!current.isConfigured) { lastError = "not configured"; _status.value = LiveStatus.ERROR; return@launch }
        peerUsername = current.peerUsername
        lastError = null
        _status.value = LiveStatus.CONNECTING
        val url = current.backendUrl.replace(Regex("^http"), "ws") + "/v1/live"
        socket = client.newWebSocket(Request.Builder().url(url).header("X-Installation-ID", current.installationId).build(), listener)
    }
    fun stop() { flush(); socket?.send(JSONObject().put("type", "end").put("streamId", streamId ?: "").toString()); socket?.close(1000, "stopped"); socket = null; streamId = null; outgoing.clear(); _status.value = LiveStatus.OFF }
    fun beginLocalStream(periodMs: Int) { if (_status.value != LiveStatus.LIVE) return; samplePeriodMs = periodMs; streamId = UUID.randomUUID().toString(); nextIndex = 0; socket?.send(JSONObject().put("type", "start").put("streamId", streamId).put("recipientUsername", peerUsername).put("samplePeriodMs", periodMs).toString()) }
    fun sample(value: Int) { if (streamId == null) return; outgoing += value; if (outgoing.size >= 10) flush() }
    fun endLocalStream() { flush(); streamId?.let { socket?.send(JSONObject().put("type", "end").put("streamId", it).toString()) }; streamId = null }
    private fun flush() { val id = streamId ?: return; if (outgoing.isEmpty()) return; val values = JSONArray(outgoing); socket?.send(JSONObject().put("type", "samples").put("streamId", id).put("startIndex", nextIndex).put("amplitudes", values).toString()); nextIndex += outgoing.size; outgoing.clear() }
    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) { lastError = null; _status.value = LiveStatus.LIVE }
        override fun onMessage(webSocket: WebSocket, text: String) { val event = JSONObject(text); when (event.getString("type")) { "start" -> { remoteStream = event.getString("streamId"); samplePeriodMs = event.getInt("samplePeriodMs"); expectedIndex = 0 }; "samples" -> receive(event); "peerUnavailable" -> _status.value = LiveStatus.PEER_UNAVAILABLE; "error" -> _status.value = LiveStatus.ERROR; "end" -> remoteStream = null } }
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { lastError = "${response?.code ?: "network"}: ${t.message}"; _status.value = LiveStatus.ERROR }
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { if (_status.value != LiveStatus.OFF) _status.value = LiveStatus.OFF }
    }
    private fun receive(event: JSONObject) {
        if (event.optString("streamId") != remoteStream || event.getInt("startIndex") != expectedIndex) return
        val values = event.getJSONArray("amplitudes"); expectedIndex += values.length()
        val samples = List(values.length()) { values.getInt(it) }
        // Small batches provide a bounded ~100 ms jitter buffer and never overlap durable playback.
        if (AndroidTouchPlayer(context).play(Touch(samplePeriodMs, samples))) {
            receivedBatchCount++
            lastPlaybackAtMs = System.currentTimeMillis()
        }
    }
}
