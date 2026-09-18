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
    val audio: AudioAttachment? = null,
    val audioPlayedAt: Long? = null,
)

data class OutboxTouch(
    val clientMessageId: String,
    val recipientUsername: String,
    val touch: Touch,
    val createdAt: Long,
    val status: OutboxStatus,
    val error: String?,
    val audio: AudioAttachment? = null,
)

/** A small, self-contained AAC-LC/MP4 voice attachment. */
data class AudioAttachment(
    val codec: String = CODEC,
    val sampleRateHz: Int = SAMPLE_RATE_HZ,
    val channelCount: Int = CHANNEL_COUNT,
    val durationMs: Int,
    val data: ByteArray,
) {
    companion object {
        const val CODEC = "aac-lc"
        const val SAMPLE_RATE_HZ = 16_000
        const val CHANNEL_COUNT = 1
        const val MAX_DURATION_MS = 30_000
        const val MAX_BYTES = 256 * 1024
    }
}

internal fun Touch.toBytes(): ByteArray = ByteArray(amplitudes.size) { amplitudes[it].toByte() }

internal fun ByteArray.toTouch(samplePeriodMillis: Int): Touch =
    Touch(samplePeriodMillis = samplePeriodMillis, amplitudes = map { it.toInt() and 0xff })
