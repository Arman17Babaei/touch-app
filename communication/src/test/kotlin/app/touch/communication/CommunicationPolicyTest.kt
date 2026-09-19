package app.touch.communication

import app.touch.core.Touch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommunicationPolicyTest {
    @Test fun liveErrorsHaveReadableReasonAndCode() {
        assertEquals("Recipient is unavailable (RECIPIENT_UNAVAILABLE)", liveErrorMessage("RECIPIENT_UNAVAILABLE"))
        assertEquals("Live call failed (SOMETHING_NEW)", liveErrorMessage("SOMETHING_NEW"))
    }
    @Test
    fun configuredAllowsContactSelectionAfterRegistration() {
        assertTrue(
            CommunicationSettings(
                installationId = "id",
                backendUrl = "http://server:8080",
                username = "phone",
                peerUsername = "watch",
            ).isConfigured,
        )
        assertTrue(CommunicationSettings(installationId = "id", backendUrl = "http://server:8080", username = "phone").isConfigured)
        assertFalse(CommunicationSettings(installationId = "id").isConfigured)
    }

    @Test
    fun byteEncodingPreservesUnsignedAmplitudes() {
        val touch = Touch(amplitudes = listOf(0, 1, 127, 128, 255))
        assertEquals(touch, touch.toBytes().toTouch(touch.samplePeriodMillis))
    }

    @Test
    fun installationIdIsGeneratedOnce() {
        var generations = 0
        val generated = chooseInstallationId("") { generations++; "new-id" }
        val persisted = chooseInstallationId(generated) { generations++; "wrong-id" }
        assertEquals("new-id", persisted)
        assertEquals(1, generations)
    }

    @Test
    fun transientHttpFailuresRetryButClientErrorsDoNot() {
        assertTrue(shouldRetryHttp(408))
        assertTrue(shouldRetryHttp(429))
        assertTrue(shouldRetryHttp(503))
        assertFalse(shouldRetryHttp(404))
        assertFalse(shouldRetryHttp(409))
    }

    @Test
    fun autoPlayQueryUsesFiveMinuteCutoffAndFifoOrdering() {
        val now = 10_000_000L
        val cutoff = now - 5 * 60_000L
        val newer = inbox("b", cutoff + 2)
        val older = inbox("a", cutoff + 1)
        val candidates = listOf(newer, older).filter { it.playedAt == null && it.createdAt >= cutoff }
            .sortedWith(compareBy(InboxTouchEntity::createdAt, InboxTouchEntity::id))
        assertEquals(listOf("a", "b"), candidates.map { it.id })
        assertFalse(inbox("expired", cutoff - 1).createdAt >= cutoff)
    }

    private fun inbox(id: String, createdAt: Long) = InboxTouchEntity(
        id = id,
        clientMessageId = "client-$id",
        senderUsername = "sender",
        samplePeriodMillis = 10,
        amplitudes = byteArrayOf(32),
        createdAt = createdAt,
        receivedAt = createdAt,
    )
}
