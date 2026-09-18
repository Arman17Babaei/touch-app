package app.touch.communication

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.AudioManager
import android.media.AudioDeviceInfo
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Base64
import android.os.Build
import java.nio.ByteBuffer
import java.util.TreeMap
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

data class LiveAudioState(
    val microphoneEnabled: Boolean = false,
    val microphoneAvailable: Boolean = false,
    val outputEnabled: Boolean = false,
    val privateRoute: Boolean = false,
)

/** AAC access-unit capture/playback for the foreground WebSocket call. */
internal class LiveAudioEngine(
    private val context: Context,
    private val scope: CoroutineScope,
    private val send: (JSONObject) -> Unit,
) {
    private val _state = MutableStateFlow(LiveAudioState())
    val state: StateFlow<LiveAudioState> = _state
    private var callId: String? = null
    private var streamId: String? = null
    private var sequence = 0
    private var record: AudioRecord? = null
    private var encoder: MediaCodec? = null
    private var decoder: MediaCodec? = null
    private var track: AudioTrack? = null
    private var captureJob: Job? = null
    private var playbackJob: Job? = null
    private var microphoneEnabled = false
    private var outputEnabled = false
    private var decoderStreamId: String? = null
    private var expectedAudioSequence = 0
    private val jitterBuffer = TreeMap<Int, ByteArray>()
    private val jitterLock = Any()

    fun start(id: String) {
        stop()
        callId = id
        val privateRoute = hasPrivateHeadphones(context)
        selectOutputRoute(allowSpeaker = false)
        outputEnabled = privateRoute
        _state.value = LiveAudioState(false, hasMicrophonePermission(context), outputEnabled, privateRoute)
        if (hasMicrophonePermission(context)) setMicrophoneEnabled(true)
    }

    fun setMicrophoneEnabled(enabled: Boolean) {
        if (enabled && !hasMicrophonePermission(context)) { publish(); return }
        if (enabled == microphoneEnabled) return
        if (enabled) startCapture() else pauseCapture()
        microphoneEnabled = enabled
        publish()
    }

    fun setOutputEnabled(allowSpeaker: Boolean) {
        outputEnabled = allowSpeaker || hasPrivateHeadphones(context)
        if (outputEnabled) {
            selectOutputRoute(allowSpeaker)
            track?.runCatching { if (playState != AudioTrack.PLAYSTATE_PLAYING) play() }
        } else {
            stopTrackPlayback()
            clearOutputRoute()
        }
        publish()
    }

    fun onRouteChanged() {
        if (!hasPrivateHeadphones(context) && outputEnabled) {
            // A route loss is never allowed to turn into implicit speaker audio.
            outputEnabled = false
            stopTrackPlayback()
            clearOutputRoute()
        }
        publish()
    }

    fun receive(event: JSONObject) {
        when (event.optString("type")) {
            "audioStart" -> {
                decoderStreamId = event.optString("streamId")
                releaseDecoder()
                playbackJob?.cancel(); playbackJob = null
                expectedAudioSequence = 0
                synchronized(jitterLock) { jitterBuffer.clear() }
                createDecoder()
            }
            "audioFrame" -> if (event.optString("streamId") == decoderStreamId) {
                val data = runCatching { Base64.decode(event.getString("audioData"), Base64.DEFAULT) }.getOrNull() ?: return
                val audioSequence = event.optInt("audioSequence", -1)
                if (audioSequence >= expectedAudioSequence && data.isNotEmpty() && data.size <= 16 * 1024) {
                    synchronized(jitterLock) {
                        if (jitterBuffer.size < MAX_JITTER_FRAMES) jitterBuffer[audioSequence] = data
                    }
                    startPlaybackIfReady()
                }
            }
            "audioEnd" -> if (event.optString("streamId") == decoderStreamId) {
                decoderStreamId = null
                playbackJob?.cancel(); playbackJob = null
                synchronized(jitterLock) { jitterBuffer.clear() }
                releaseDecoder()
            }
        }
    }

    fun stop() {
        captureJob?.cancel(); captureJob = null
        playbackJob?.cancel(); playbackJob = null
        streamId?.let { id -> callId?.let { call -> send(JSONObject().put("type", "audioEnd").put("callId", call).put("streamId", id)) } }
        streamId = null
        record?.runCatching { stop() }; record?.release(); record = null
        encoder?.runCatching { stop() }; encoder?.release(); encoder = null
        releaseDecoder()
        clearOutputRoute()
        synchronized(jitterLock) { jitterBuffer.clear() }
        callId = null; microphoneEnabled = false; outputEnabled = false; decoderStreamId = null
        publish()
    }

    private fun startCapture() {
        val id = callId ?: return
        if (encoder == null) {
            val min = AudioRecord.getMinBufferSize(16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(2048)
            record = AudioRecord.Builder().setAudioSource(android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION).setAudioFormat(
                AudioFormat.Builder().setSampleRate(16_000).setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build(),
            ).setBufferSizeInBytes(min * 2).build().also { value ->
                if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(value.audioSessionId)?.enabled = true
                if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(value.audioSessionId)?.enabled = true
            }
            encoder = MediaCodec.createEncoderByType(MIME).apply {
                configure(MediaFormat.createAudioFormat(MIME, 16_000, 1).apply {
                    setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    setInteger(MediaFormat.KEY_BIT_RATE, 24_000)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, min)
                }, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }
            streamId = UUID.randomUUID().toString()
            sequence = 0
            send(JSONObject().put("type", "audioStart").put("callId", id).put("streamId", streamId).put("codec", "aac-lc").put("sampleRateHz", 16_000).put("channelCount", 1))
            captureJob = scope.launch { captureLoop(min) }
        }
        record?.startRecording()
    }

    private fun pauseCapture() { record?.runCatching { stop() } }

    private suspend fun captureLoop(bufferSize: Int) {
        val bytes = ByteArray(bufferSize)
        while (true) {
            if (!microphoneEnabled) { delay(20); continue }
            val count = record?.read(bytes, 0, bytes.size) ?: -1
            if (count <= 0) continue
            val codec = encoder ?: continue
            val index = codec.dequeueInputBuffer(10_000)
            if (index >= 0) {
                codec.getInputBuffer(index)?.apply { clear(); put(bytes, 0, count) }
                codec.queueInputBuffer(index, 0, count, System.nanoTime() / 1_000, 0)
            }
            drainEncoder(codec)
        }
    }

    private fun drainEncoder(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val index = codec.dequeueOutputBuffer(info, 0)
            if (index < 0) return
            if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                val source = codec.getOutputBuffer(index) ?: break
                val data = ByteArray(info.size)
                source.position(info.offset); source.limit(info.offset + info.size); source.get(data)
                callId?.let { call -> streamId?.let { stream -> send(JSONObject().put("type", "audioFrame").put("callId", call).put("streamId", stream).put("audioSequence", sequence++).put("audioData", Base64.encodeToString(data, Base64.NO_WRAP))) } }
            }
            codec.releaseOutputBuffer(index, false)
        }
    }

    private fun createDecoder() {
        // MPEG-4 AudioSpecificConfig: AAC-LC (2), 16 kHz index (8), mono (1).
        // 0x12,0x08 describes 44.1 kHz mono and made 16 kHz frames decode as
        // low-pitched beeps on the physical phone/watch.
        val format = MediaFormat.createAudioFormat(MIME, 16_000, 1).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(byteArrayOf(0x14, 0x08)))
        }
        decoder = MediaCodec.createDecoderByType(MIME).apply { configure(format, null, null, 0); start() }
        track = AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()).setAudioFormat(
            AudioFormat.Builder().setSampleRate(16_000).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
        ).setBufferSizeInBytes(16_000).setTransferMode(AudioTrack.MODE_STREAM).build().also { if (outputEnabled) it.play() }
    }

    private fun startPlaybackIfReady() {
        if (playbackJob != null || synchronized(jitterLock) { jitterBuffer.size < INITIAL_JITTER_FRAMES }) return
        playbackJob = scope.launch {
            while (decoderStreamId != null) {
                val data = synchronized(jitterLock) { jitterBuffer.remove(expectedAudioSequence) }
                if (data != null) {
                    decode(data)
                    expectedAudioSequence++
                } else {
                    // AAC access units can be decoded independently. If a bounded relay
                    // queue drops one, resume from the next frame rather than feeding
                    // MediaCodec a permanently misordered stream.
                    val next = synchronized(jitterLock) { if (jitterBuffer.isEmpty()) null else jitterBuffer.firstKey() }
                    if (next != null && next > expectedAudioSequence) expectedAudioSequence = next
                }
                delay(AAC_FRAME_MILLIS)
            }
        }
    }

    private fun decode(data: ByteArray) {
        val codec = decoder ?: return
        val input = codec.dequeueInputBuffer(0)
        if (input >= 0) { codec.getInputBuffer(input)?.apply { clear(); put(data) }; codec.queueInputBuffer(input, 0, data.size, System.nanoTime() / 1_000, 0) }
        val info = MediaCodec.BufferInfo()
        while (true) {
            val output = codec.dequeueOutputBuffer(info, 0)
            if (output < 0) return
            if (info.size > 0 && outputEnabled) {
                val buffer = codec.getOutputBuffer(output) ?: break
                val pcm = ByteArray(info.size); buffer.position(info.offset); buffer.limit(info.offset + info.size); buffer.get(pcm)
                track?.write(pcm, 0, pcm.size)
            }
            codec.releaseOutputBuffer(output, false)
        }
    }

    private fun releaseDecoder() {
        decoder?.runCatching { stop() }
        decoder?.runCatching { release() }
        decoder = null
        releaseTrack()
    }
    private fun releaseTrack() {
        stopTrackPlayback()
        track?.runCatching { release() }
        track = null
    }
    private fun stopTrackPlayback() {
        track?.runCatching {
            if (state == AudioTrack.STATE_INITIALIZED && playState == AudioTrack.PLAYSTATE_PLAYING) pause()
            if (state == AudioTrack.STATE_INITIALIZED) flush()
        }
    }
    private fun selectOutputRoute(allowSpeaker: Boolean) {
        val manager = context.getSystemService(AudioManager::class.java)
        manager.mode = AudioManager.MODE_IN_COMMUNICATION
        if (Build.VERSION.SDK_INT >= 31) {
            val privateDevice = manager.availableCommunicationDevices.firstOrNull { device ->
                device.type in setOf(AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_HEARING_AID, AudioDeviceInfo.TYPE_BLE_HEADSET)
            }
            val target = privateDevice ?: if (allowSpeaker) manager.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER } else null
            if (target != null) manager.runCatching { setCommunicationDevice(target) }
        } else {
            @Suppress("DEPRECATION")
            manager.isSpeakerphoneOn = allowSpeaker && !hasPrivateHeadphones(context)
        }
    }
    private fun clearOutputRoute() {
        val manager = context.getSystemService(AudioManager::class.java)
        if (Build.VERSION.SDK_INT >= 31) manager.runCatching { clearCommunicationDevice() }
        else {
            @Suppress("DEPRECATION")
            manager.isSpeakerphoneOn = false
        }
        manager.mode = AudioManager.MODE_NORMAL
    }
    private fun publish() { _state.value = LiveAudioState(microphoneEnabled, hasMicrophonePermission(context), outputEnabled, hasPrivateHeadphones(context)) }
    private companion object {
        const val MIME = "audio/mp4a-latm"
        const val AAC_FRAME_MILLIS = 64L
        const val INITIAL_JITTER_FRAMES = 2
        const val MAX_JITTER_FRAMES = 24
    }
}
