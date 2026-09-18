package app.touch.communication

import app.touch.core.Touch

data class CommunicationSettings(
    val installationId: String = "",
    val backendUrl: String = "",
    val username: String = "",
    val peerUsername: String = "",
    val fcmToken: String = "",
) {
    val isConfigured: Boolean
        get() = installationId.isNotBlank() && backendUrl.isNotBlank() &&
            username.isNotBlank() && peerUsername.isNotBlank()
}

enum class OutboxStatus { QUEUED, SENDING, SENT, FAILED }

data class InboxTouch(
    val id: String,
    val senderUsername: String,
    val touch: Touch,
    val createdAt: Long,
    val receivedAt: Long,
    val playedAt: Long?,
)

data class OutboxTouch(
    val clientMessageId: String,
    val recipientUsername: String,
    val touch: Touch,
    val createdAt: Long,
    val status: OutboxStatus,
    val error: String?,
)

internal fun Touch.toBytes(): ByteArray = ByteArray(amplitudes.size) { amplitudes[it].toByte() }

internal fun ByteArray.toTouch(samplePeriodMillis: Int): Touch =
    Touch(samplePeriodMillis = samplePeriodMillis, amplitudes = map { it.toInt() and 0xff })
