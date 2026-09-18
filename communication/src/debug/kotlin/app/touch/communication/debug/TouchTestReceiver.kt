package app.touch.communication.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.touch.communication.TouchCommunication
import app.touch.core.Touch
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/** Explicit, debug-source-set-only ADB control plane. It is absent from release artifacts. */
class TouchTestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = runBlocking {
        val communication = TouchCommunication.get(context.applicationContext)
        try {
            when (intent.getStringExtra("command")) {
                "configure" -> communication.configure(intent.require("backendUrl"), intent.require("username"), intent.require("peerUsername"))
                "enqueueTouch" -> communication.enqueue(Touch(intent.getIntExtra("samplePeriodMs", 10), intent.require("amplitudes").split(',').filter { it.isNotBlank() }.map { it.toInt() }), intent.require("clientMessageId"))
                "sync" -> communication.syncInbox()
                "liveStart" -> communication.startLive()
                "liveBegin" -> communication.beginLiveTouch(intent.getIntExtra("samplePeriodMs", 10))
                "liveSample" -> communication.streamSample(intent.getIntExtra("amplitude", 0))
                "liveEnd" -> communication.endLiveTouch()
                "liveStop" -> communication.stopLive()
                "state" -> Unit
                else -> error("unknown command")
            }
            setResultCode(0); setResultData(state(communication).toString())
        } catch (error: Throwable) { setResultCode(1); setResultData(JSONObject().put("error", error.message ?: error.javaClass.simpleName).toString()) }
    }
    private suspend fun state(c: TouchCommunication): JSONObject = JSONObject().put("settings", JSONObject().put("backendUrl", c.settingsStore.current().backendUrl).put("username", c.settingsStore.current().username).put("peerUsername", c.settingsStore.current().peerUsername)).put("liveStatus", c.liveStatus.value.name).put("liveError", c.live.lastError).put("liveReceivedBatchCount", c.live.receivedBatchCount).put("livePlaybackAtMs", c.live.lastPlaybackAtMs).put("outbox", JSONArray(c.dao.allOutbox().map { JSONObject().put("clientMessageId", it.clientMessageId).put("status", it.status).put("serverTouchId", it.serverTouchId) })).put("inbox", JSONArray(c.dao.allInbox().map { JSONObject().put("touchId", it.id).put("clientMessageId", it.clientMessageId).put("samplePeriodMs", it.samplePeriodMillis).put("payloadHash", it.amplitudes.sha256()).put("playedAtMs", it.playedAt) }))
    private fun Intent.require(name: String) = getStringExtra(name) ?: error("missing $name")
    private fun ByteArray.sha256() = MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { "%02x".format(it) }
}
