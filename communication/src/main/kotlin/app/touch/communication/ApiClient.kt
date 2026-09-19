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
internal data class SendTouchRequest(val clientMessageId: String, val recipientUsername: String, val samplePeriodMs: Int, val amplitudes: List<Int>, val audio: AudioAttachment?)
internal data class TouchAccepted(val touchId: String, val clientMessageId: String, val acceptedAtMs: Long, val duplicate: Boolean)
internal data class RemoteTouch(val touchId: String, val clientMessageId: String, val senderUsername: String, val samplePeriodMs: Int, val amplitudes: List<Int>, val createdAtMs: Long, val audio: AudioAttachment?)
internal interface TouchApi {
    suspend fun register(settings: CommunicationSettings, platform: String)
    suspend fun send(settings: CommunicationSettings, item: OutboxTouchEntity): TouchAccepted
    suspend fun pending(settings: CommunicationSettings): List<RemoteTouch>
    suspend fun ack(settings: CommunicationSettings, touchId: String, state: String)
    suspend fun installationStatus(settings: CommunicationSettings): InstallationStatus
    suspend fun contacts(settings: CommunicationSettings): List<Contact>
    suspend fun addContact(settings: CommunicationSettings, username: String): Contact
    suspend fun removeContact(settings: CommunicationSettings, username: String)
    suspend fun startPushTest(settings: CommunicationSettings): PushTest
    suspend fun pushTest(settings: CommunicationSettings, id: String): PushTest
    suspend fun ackPushTest(settings: CommunicationSettings, id: String)
    suspend fun notificationEvent(settings: CommunicationSettings, id: String, event: String)
    suspend fun uploadDiagnostics(settings: CommunicationSettings, events: List<DiagnosticEventEntity>)
}

/** The only org.json boundary; all callers use typed DTOs. */
internal object ApiJson {
    fun register(v: RegisterInstallationRequest) = JSONObject().put("username", v.username).put("platform", v.platform).put("fcmToken", v.fcmToken)
    fun send(v: SendTouchRequest) = JSONObject().put("clientMessageId", v.clientMessageId).put("recipientUsername", v.recipientUsername).put("samplePeriodMs", v.samplePeriodMs).put("amplitudes", JSONArray(v.amplitudes)).also { root -> v.audio?.let { audio -> root.put("audio", audioJson(audio)) } }
    fun ack(state: String) = JSONObject().put("state", state)
    fun accepted(j: JSONObject) = TouchAccepted(j.getString("touchId"), j.getString("clientMessageId"), j.getLong("acceptedAtMs"), j.getBoolean("duplicate"))
    fun pending(j: JSONObject): List<RemoteTouch> = j.getJSONArray("touches").let { values -> List(values.length()) { i -> values.getJSONObject(i).let { item -> RemoteTouch(item.getString("touchId"), item.getString("clientMessageId"), item.getString("senderUsername"), item.getInt("samplePeriodMs"), item.getJSONArray("amplitudes").let { a -> List(a.length()) { a.getInt(it) } }, item.getLong("createdAtMs"), item.optJSONObject("audio")?.let(::audioFromJson)) } } }
    private fun audioJson(audio: AudioAttachment) = JSONObject().put("codec", audio.codec).put("sampleRateHz", audio.sampleRateHz).put("channelCount", audio.channelCount).put("durationMs", audio.durationMs).put("data", android.util.Base64.encodeToString(audio.data, android.util.Base64.NO_WRAP))
    private fun audioFromJson(json: JSONObject) = AudioAttachment(json.getString("codec"), json.getInt("sampleRateHz"), json.getInt("channelCount"), json.getInt("durationMs"), android.util.Base64.decode(json.getString("data"), android.util.Base64.DEFAULT))
    fun error(text: String): Pair<String?, String?> = runCatching { JSONObject(text).getJSONObject("error").let { error -> error.optString("code").takeIf(String::isNotBlank) to error.optString("message").takeIf(String::isNotBlank) } }.getOrDefault(null to null)
}

internal class TouchApiClient : TouchApi {
    override suspend fun register(s: CommunicationSettings, platform: String) { request(s, "PUT", "/v1/installations/${s.installationId}", ApiJson.register(RegisterInstallationRequest(s.username, platform, s.fcmToken))) }
    override suspend fun send(s: CommunicationSettings, item: OutboxTouchEntity): TouchAccepted = ApiJson.accepted(request(s, "POST", "/v1/touches", ApiJson.send(SendTouchRequest(item.clientMessageId, item.recipientUsername, item.samplePeriodMillis, item.amplitudes.map { it.toInt() and 255 }, audioAttachment(item.audioCodec, item.audioSampleRateHz, item.audioChannelCount, item.audioDurationMs, item.audioData)))))
    override suspend fun pending(s: CommunicationSettings): List<RemoteTouch> = ApiJson.pending(request(s, "GET", "/v1/touches?state=pending", null))
    override suspend fun ack(s: CommunicationSettings, touchId: String, state: String) { request(s, "POST", "/v1/touches/$touchId/ack", ApiJson.ack(state), false) }
    override suspend fun installationStatus(s: CommunicationSettings): InstallationStatus = request(s,"GET","/v1/installations/${s.installationId}",null).let { j -> InstallationStatus(j.getString("installationId"),j.getString("username"),j.getString("platform"),j.getLong("updatedAtMs"),j.getBoolean("fcmTokenPresent"),j.optString("fcmTokenFingerprint").takeIf(String::isNotBlank),j.optString("lastPushTestStatus").takeIf(String::isNotBlank)) }
    override suspend fun contacts(s: CommunicationSettings): List<Contact> = request(s,"GET","/v1/contacts",null).getJSONArray("contacts").let { a -> List(a.length()) { i -> a.getJSONObject(i).let { Contact(it.getString("username"),it.getBoolean("saved"),if(it.has("lastUsedAtMs"))it.getLong("lastUsedAtMs") else null) } } }
    override suspend fun addContact(s: CommunicationSettings, username: String): Contact = request(s,"POST","/v1/contacts",JSONObject().put("username",username)).let { Contact(it.getString("username"),it.getBoolean("saved"),null) }
    override suspend fun removeContact(s: CommunicationSettings, username: String) { request(s,"DELETE","/v1/contacts/${java.net.URLEncoder.encode(username,"UTF-8")}",null,false) }
    override suspend fun startPushTest(s: CommunicationSettings): PushTest = push(request(s,"POST","/v1/installations/${s.installationId}/push-tests",JSONObject()))
    override suspend fun pushTest(s: CommunicationSettings, id: String): PushTest = push(request(s,"GET","/v1/push-tests/$id",null))
    override suspend fun ackPushTest(s: CommunicationSettings, id: String) { request(s,"POST","/v1/push-tests/$id/ack",JSONObject(),false) }
    override suspend fun notificationEvent(s: CommunicationSettings,id:String,event:String) { request(s,"POST","/v1/notifications/$id/events",JSONObject().put("event",event).put("occurredAtMs",System.currentTimeMillis()),false) }
    override suspend fun uploadDiagnostics(s: CommunicationSettings, events: List<DiagnosticEventEntity>) {
        val items=JSONArray();events.forEach { e -> items.put(JSONObject().put("eventId",e.eventId).put("occurredAtMs",e.occurredAtMs).put("severity",e.severity).put("category",e.category).put("name",e.name).putOpt("callId",e.callId).putOpt("touchId",e.touchId).putOpt("clientMessageId",e.clientMessageId).put("message",e.message).put("attributes",JSONObject(e.attributesJson))) }
        request(s,"POST","/v1/diagnostics/events",JSONObject().put("events",items),false)
    }
    private fun push(j:JSONObject)=PushTest(j.getString("testId"),j.getString("status"),j.getLong("createdAtMs"),if(j.has("completedAtMs"))j.getLong("completedAtMs")else null)
    private suspend fun request(s: CommunicationSettings, method: String, path: String, body: JSONObject?, expectBody: Boolean = true): JSONObject = withContext(Dispatchers.IO) {
        CommunicationLog.debug("HTTP request method=$method path=$path installation=${s.installationId.take(8)}")
        val c = (URL(s.backendUrl + path).openConnection() as HttpURLConnection).apply { requestMethod = method; connectTimeout = 10_000; readTimeout = 15_000; setRequestProperty("Accept", "application/json"); setRequestProperty("X-Installation-ID", s.installationId); if (body != null) { doOutput = true; setRequestProperty("Content-Type", "application/json"); outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body.toString()) } } }
        try { val status = c.responseCode; val text = (if (status in 200..299) c.inputStream else c.errorStream)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty(); if (status !in 200..299) { val (code, message) = ApiJson.error(text); CommunicationLog.warn("HTTP response method=$method path=$path status=$status code=${code ?: "none"} message=${message ?: "none"}"); throw ApiException(status, code, message ?: "HTTP $status") }; CommunicationLog.debug("HTTP response method=$method path=$path status=$status"); if (!expectBody || text.isBlank()) JSONObject() else JSONObject(text) } catch (error: IOException) { CommunicationLog.warn("HTTP failure method=$method path=$path error=${error.message}", error); throw error } finally { c.disconnect() }
    }
}
