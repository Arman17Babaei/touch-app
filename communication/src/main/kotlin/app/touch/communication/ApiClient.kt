package app.touch.communication

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal class ApiException(val statusCode: Int, val code: String? = null, message: String) : IOException(message)
internal data class RegisterInstallationRequest(val username: String, val platform: String, val fcmToken: String)
internal data class SendTouchRequest(val clientMessageId: String, val recipientUsername: String, val samplePeriodMs: Int, val amplitudes: List<Int>)
internal data class TouchAccepted(val touchId: String, val clientMessageId: String, val acceptedAtMs: Long, val duplicate: Boolean)
internal data class RemoteTouch(val touchId: String, val clientMessageId: String, val senderUsername: String, val samplePeriodMs: Int, val amplitudes: List<Int>, val createdAtMs: Long)
internal interface TouchApi { suspend fun register(settings: CommunicationSettings, platform: String); suspend fun send(settings: CommunicationSettings, item: OutboxTouchEntity): TouchAccepted; suspend fun pending(settings: CommunicationSettings): List<RemoteTouch>; suspend fun ack(settings: CommunicationSettings, touchId: String, state: String) }

/** The only org.json boundary; all callers use typed DTOs. */
internal object ApiJson {
    fun register(v: RegisterInstallationRequest) = JSONObject().put("username", v.username).put("platform", v.platform).put("fcmToken", v.fcmToken)
    fun send(v: SendTouchRequest) = JSONObject().put("clientMessageId", v.clientMessageId).put("recipientUsername", v.recipientUsername).put("samplePeriodMs", v.samplePeriodMs).put("amplitudes", JSONArray(v.amplitudes))
    fun ack(state: String) = JSONObject().put("state", state)
    fun accepted(j: JSONObject) = TouchAccepted(j.getString("touchId"), j.getString("clientMessageId"), j.getLong("acceptedAtMs"), j.getBoolean("duplicate"))
    fun pending(j: JSONObject): List<RemoteTouch> = j.getJSONArray("touches").let { values -> List(values.length()) { i -> values.getJSONObject(i).let { item -> RemoteTouch(item.getString("touchId"), item.getString("clientMessageId"), item.getString("senderUsername"), item.getInt("samplePeriodMs"), item.getJSONArray("amplitudes").let { a -> List(a.length()) { a.getInt(it) } }, item.getLong("createdAtMs")) } } }
    fun error(text: String): Pair<String?, String?> = runCatching { JSONObject(text).getJSONObject("error").let { it.optString("code", null) to it.optString("message", null) } }.getOrDefault(null to null)
}

internal class TouchApiClient : TouchApi {
    override suspend fun register(s: CommunicationSettings, platform: String) { request(s, "PUT", "/v1/installations/${s.installationId}", ApiJson.register(RegisterInstallationRequest(s.username, platform, s.fcmToken))) }
    override suspend fun send(s: CommunicationSettings, item: OutboxTouchEntity): TouchAccepted = ApiJson.accepted(request(s, "POST", "/v1/touches", ApiJson.send(SendTouchRequest(item.clientMessageId, item.recipientUsername, item.samplePeriodMillis, item.amplitudes.map { it.toInt() and 255 }))))
    override suspend fun pending(s: CommunicationSettings): List<RemoteTouch> = ApiJson.pending(request(s, "GET", "/v1/touches?state=pending", null))
    override suspend fun ack(s: CommunicationSettings, touchId: String, state: String) { request(s, "POST", "/v1/touches/$touchId/ack", ApiJson.ack(state), false) }
    private suspend fun request(s: CommunicationSettings, method: String, path: String, body: JSONObject?, expectBody: Boolean = true): JSONObject = withContext(Dispatchers.IO) {
        CommunicationLog.debug("HTTP request method=$method path=$path installation=${s.installationId.take(8)}")
        val c = (URL(s.backendUrl + path).openConnection() as HttpURLConnection).apply { requestMethod = method; connectTimeout = 10_000; readTimeout = 15_000; setRequestProperty("Accept", "application/json"); setRequestProperty("X-Installation-ID", s.installationId); if (body != null) { doOutput = true; setRequestProperty("Content-Type", "application/json"); outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body.toString()) } } }
        try { val status = c.responseCode; val text = (if (status in 200..299) c.inputStream else c.errorStream)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty(); if (status !in 200..299) { val (code, message) = ApiJson.error(text); CommunicationLog.warn("HTTP response method=$method path=$path status=$status code=${code ?: "none"} message=${message ?: "none"}"); throw ApiException(status, code, message ?: "HTTP $status") }; CommunicationLog.debug("HTTP response method=$method path=$path status=$status"); if (!expectBody || text.isBlank()) JSONObject() else JSONObject(text) } catch (error: IOException) { CommunicationLog.warn("HTTP failure method=$method path=$path error=${error.message}", error); throw error } finally { c.disconnect() }
    }
}
