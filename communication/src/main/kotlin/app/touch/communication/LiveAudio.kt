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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

data class LiveAudioState(
    val microphoneEnabled: Boolean = false,
    val microphoneAvailable: Boolean = false,
    val outputEnabled: Boolean = false,
    val privateRoute: Boolean = false,
    val encodedFrames: Long = 0,
    val decodedFrames: Long = 0,
    val droppedFrames: Long = 0,
    val decoderErrors: Long = 0,
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
    private var captureGeneration = 0L
    private var microphoneEnabled = false
    private var outputEnabled = false
    private var decoderStreamId: String? = null
    private var expectedAudioSequence = 0
    private val jitterBuffer = TreeMap<Int, ByteArray>()
    private val jitterLock = Any()
    private var encodedFrames=0L;private var decodedFrames=0L;private var droppedFrames=0L;private var decoderErrors=0L

    fun start(id: String) {
        stop()
        callId = id
        val privateRoute = hasPrivateHeadphones(context)
        selectOutputRoute(allowSpeaker = false)
        outputEnabled = privateRoute
        encodedFrames=0;decodedFrames=0;droppedFrames=0;decoderErrors=0
        _state.value = LiveAudioState(false, hasMicrophonePermission(context), outputEnabled, privateRoute)
        if (hasMicrophonePermission(context)) setMicrophoneEnabled(true)
    }

    fun setMicrophoneEnabled(enabled: Boolean) {
        if (enabled && !hasMicrophonePermission(context)) { publish(); return }
        if (enabled == microphoneEnabled) return
        microphoneEnabled = enabled
        if (enabled) startCapture() else stopCaptureStream()
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
                createDecoder(runCatching { Base64.decode(event.getString("codecConfig"),Base64.DEFAULT) }.getOrNull())
            }
            "audioFrame" -> if (event.optString("streamId") == decoderStreamId) {
                val data = runCatching { Base64.decode(event.getString("audioData"), Base64.DEFAULT) }.getOrNull() ?: return
                val audioSequence = event.optInt("audioSequence", -1)
                if (audioSequence >= expectedAudioSequence && data.isNotEmpty() && data.size <= 16 * 1024) {
                    synchronized(jitterLock) {
                        if (jitterBuffer.size < MAX_JITTER_FRAMES) jitterBuffer[audioSequence] = data else droppedFrames++
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

    fun stop(notifyPeer: Boolean = true) {
        microphoneEnabled = false
        stopCaptureStream(notifyPeer)
        playbackJob?.cancel(); playbackJob = null
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
            val activeRecord = checkNotNull(record)
            val activeEncoder = checkNotNull(encoder)
            val activeCall = id
            val activeStream = checkNotNull(streamId)
            val generation = ++captureGeneration
            captureJob = scope.launch { captureLoop(min, activeRecord, activeEncoder, activeCall, activeStream, generation) }
        }
        record?.startRecording()
    }

    private fun stopCaptureStream(notifyPeer: Boolean = true) {
        captureGeneration++
        captureJob?.cancel();captureJob=null
        // AudioRecord.stop() unblocks a pending read. The capture coroutine owns
        // releasing both objects in finally, so MediaCodec cannot be released
        // while dequeueInputBuffer/dequeueOutputBuffer is still executing.
        record?.runCatching{stop()}
        record=null
        encoder=null
        if (notifyPeer) streamId?.let{id->callId?.let{call->send(JSONObject().put("type","audioEnd").put("callId",call).put("streamId",id))}}
        streamId=null
    }

    private suspend fun captureLoop(bufferSize: Int, activeRecord: AudioRecord, activeEncoder: MediaCodec, activeCall: String, activeStream: String, generation: Long) {
        val bytes = ByteArray(bufferSize)
        try {
            while (currentCoroutineContext().isActive && generation == captureGeneration) {
                val count = activeRecord.read(bytes, 0, bytes.size)
                if (count <= 0 || generation != captureGeneration) continue
                val index = activeEncoder.dequeueInputBuffer(10_000)
                if (index >= 0) {
                    activeEncoder.getInputBuffer(index)?.apply { clear(); put(bytes, 0, count) }
                    activeEncoder.queueInputBuffer(index, 0, count, System.nanoTime() / 1_000, 0)
                }
                drainEncoder(activeEncoder, activeCall, activeStream, generation)
            }
        } catch (_: IllegalStateException) {
            // Expected when AudioRecord.stop() interrupts a vendor codec/read.
        } catch (_: MediaCodec.CodecException) {
            // A terminal codec error ends this stream without crashing the app.
        } finally {
            activeRecord.runCatching { stop() }
            activeRecord.runCatching { release() }
            activeEncoder.runCatching { stop() }
            activeEncoder.runCatching { release() }
        }
    }

    private fun drainEncoder(codec: MediaCodec, activeCall: String, activeStream: String, generation: Long) {
        val info = MediaCodec.BufferInfo()
        while (generation == captureGeneration) {
            val index = codec.dequeueOutputBuffer(info, 0)
            if(index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){
                val config=codec.outputFormat.getByteBuffer("csd-0")?.let{b->ByteArray(b.remaining()).also{b.get(it)}}?:return
                send(JSONObject().put("type","audioStart").put("callId",activeCall).put("streamId",activeStream).put("codec","aac-lc").put("sampleRateHz",16_000).put("channelCount",1).put("codecConfig",Base64.encodeToString(config,Base64.NO_WRAP)))
                continue
            }
            if (index < 0) return
            if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                val source = codec.getOutputBuffer(index) ?: break
                val data = ByteArray(info.size)
                source.position(info.offset); source.limit(info.offset + info.size); source.get(data)
                if (generation == captureGeneration) {
                    send(JSONObject().put("type", "audioFrame").put("callId", activeCall).put("streamId", activeStream).put("audioSequence", sequence++).put("presentationTimeUs",info.presentationTimeUs).put("audioData", Base64.encodeToString(data, Base64.NO_WRAP)))
                    encodedFrames++;publish()
                }
            }
            codec.releaseOutputBuffer(index, false)
        }
    }

    private fun createDecoder(codecConfig:ByteArray?) {
        if(codecConfig==null||codecConfig.isEmpty()){decoderErrors++;publish();return}
        val format = MediaFormat.createAudioFormat(MIME, 16_000, 1).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(codecConfig))
        }
        decoder = MediaCodec.createDecoderByType(MIME).apply { configure(format, null, null, 0); start() }
        track = AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()).setAudioFormat(
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
                    // AudioTrack.write() is blocking and therefore already paces PCM
                    // at the device playback rate. Delaying another AAC frame here
                    // halves voice speed and eventually overflows the jitter buffer.
                    continue
                } else {
                    // AAC access units can be decoded independently. If a bounded relay
                    // queue drops one, resume from the next frame rather than feeding
                    // MediaCodec a permanently misordered stream.
                    val next = synchronized(jitterLock) { if (jitterBuffer.isEmpty()) null else jitterBuffer.firstKey() }
                    if (next != null && next > expectedAudioSequence) {droppedFrames+=(next-expectedAudioSequence);expectedAudioSequence = next;publish()}
                }
                delay(JITTER_POLL_MILLIS)
            }
        }
    }

    private fun decode(data: ByteArray) {
        val codec = decoder ?: return
        runCatching {
            val input = codec.dequeueInputBuffer(0)
            if (input >= 0) { codec.getInputBuffer(input)?.apply { clear(); put(data) }; codec.queueInputBuffer(input, 0, data.size, System.nanoTime() / 1_000, 0) }
            val info = MediaCodec.BufferInfo()
            while (true) {
                val output = codec.dequeueOutputBuffer(info, 0)
                if (output < 0) return@runCatching
                if (info.size > 0 && outputEnabled) {
                    val buffer = codec.getOutputBuffer(output) ?: break
                    val pcm = ByteArray(info.size); buffer.position(info.offset); buffer.limit(info.offset + info.size); buffer.get(pcm)
                    track?.write(pcm, 0, pcm.size)
                    decodedFrames++;publish()
                }
                codec.releaseOutputBuffer(output, false)
            }
        }.onFailure { if (decoder === codec) { decoderErrors++; publish() } }
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
    private fun publish() { _state.value = LiveAudioState(microphoneEnabled, hasMicrophonePermission(context), outputEnabled, hasPrivateHeadphones(context),encodedFrames,decodedFrames,droppedFrames,decoderErrors) }
    private companion object {
        const val MIME = "audio/mp4a-latm"
        const val JITTER_POLL_MILLIS = 5L
        const val INITIAL_JITTER_FRAMES = 2
        const val MAX_JITTER_FRAMES = 24
    }
}
