package app.touch.communication

import android.os.Build
import java.util.UUID
import org.json.JSONObject

internal class DiagnosticReporter(private val dao: TouchDao) {
    suspend fun record(
        severity: String, category: String, name: String, message: String = "",
        callId: String? = null, touchId: String? = null, clientMessageId: String? = null,
        attributes: Map<String, Any?> = emptyMap(),
    ) {
        val safe = message.replace('\n',' ').replace('\r',' ').take(512)
        val common = attributes + mapOf("sdk" to Build.VERSION.SDK_INT, "device" to Build.MODEL.take(64))
        dao.insertDiagnostic(DiagnosticEventEntity(UUID.randomUUID().toString(),System.currentTimeMillis(),severity,category,name,callId,touchId,clientMessageId,safe,JSONObject(common).toString()))
        dao.trimDiagnostics()
    }
}
