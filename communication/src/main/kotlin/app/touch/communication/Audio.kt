package app.touch.communication

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Session-owned recorder. MediaRecorder writes AAC-LC in a standard MP4 container. */
class RecordedAudioCapture(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L
    var microphoneEnabled: Boolean = false
        private set

    fun start(enabled: Boolean): Boolean {
        if (!enabled || !hasMicrophonePermission(context)) return false
        val target = File.createTempFile("touch-audio-", ".m4a", context.cacheDir)
        return runCatching {
            @Suppress("DEPRECATION")
            val value = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(AudioAttachment.SAMPLE_RATE_HZ)
                setAudioChannels(AudioAttachment.CHANNEL_COUNT)
                setAudioEncodingBitRate(24_000)
                setOutputFile(target.absolutePath)
                prepare()
                start()
            }
            recorder = value
            file = target
            startedAt = System.currentTimeMillis()
            microphoneEnabled = true
            CommunicationLog.info("Recorded audio capture started")
            true
        }.getOrElse {
            CommunicationLog.warn("Recorded audio capture failed to start: ${it.message}", it)
            target.delete()
            false
        }
    }

    /** Pausing releases the microphone to the system and records no user audio. */
    fun setMicrophoneEnabled(enabled: Boolean) {
        val current = recorder ?: return
        if (enabled == microphoneEnabled) return
        runCatching { if (enabled) current.resume() else current.pause() }
        microphoneEnabled = enabled
    }

    fun stop(): AudioAttachment? {
        val current = recorder ?: return null
        recorder = null
        val elapsed = (System.currentTimeMillis() - startedAt).coerceIn(1, AudioAttachment.MAX_DURATION_MS.toLong()).toInt()
        val stopped = runCatching { current.stop() }
        current.reset()
        current.release()
        microphoneEnabled = false
        val target = file.also { file = null } ?: return null
        if (stopped.isFailure) {
            CommunicationLog.warn("Recorded audio capture failed to stop: ${stopped.exceptionOrNull()?.message}", stopped.exceptionOrNull())
            target.delete()
            return null
        }
        val data = runCatching { target.readBytes() }.getOrNull()
        target.delete()
        if (data == null || data.isEmpty() || data.size > AudioAttachment.MAX_BYTES) {
            CommunicationLog.warn("Recorded audio discarded bytes=${data?.size ?: -1}")
            return null
        }
        CommunicationLog.info("Recorded audio capture completed duration_ms=$elapsed bytes=${data.size}")
        return AudioAttachment(durationMs = elapsed, data = data)
    }

    fun cancel() { recorder?.let { runCatching { it.stop() }; it.release() }; recorder = null; file?.delete(); file = null; microphoneEnabled = false }
}

internal fun hasMicrophonePermission(context: Context): Boolean =
    context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

/** Conservative privacy classification. Generic Bluetooth media outputs are not auto-play routes. */
internal fun hasPrivateHeadphones(context: Context): Boolean {
    val manager = context.getSystemService(AudioManager::class.java)
    return manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any(::isPrivateOutput)
}

private fun isPrivateOutput(device: AudioDeviceInfo): Boolean =
    when (device.type) {
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_HEARING_AID -> true
            AudioDeviceInfo.TYPE_BLE_HEADSET -> Build.VERSION.SDK_INT >= 31
            else -> false
    }

/** One-at-a-time durable playback. Speaker use always needs a caller-provided explicit grant. */
internal object AudioPlaybackCoordinator {
    private var player: MediaPlayer? = null
    private var temporaryFile: File? = null
    private var callback: AudioDeviceCallback? = null
    private var callbackManager: AudioManager? = null

    suspend fun play(context: Context, dao: TouchDao, item: InboxTouch, allowSpeaker: Boolean): Boolean =
        playAttachment(context, item.audio, allowSpeaker) { daoMark(dao, item.id) }

    suspend fun playAttachment(context: Context, audio: AudioAttachment?, allowSpeaker: Boolean, onFinished: () -> Unit = {}): Boolean = withContext(Dispatchers.Main) {
        audio ?: return@withContext false
        if (!allowSpeaker && !hasPrivateHeadphones(context)) return@withContext false
        stop()
        val output = context.getSystemService(AudioManager::class.java)
        val target = File.createTempFile("touch-play-", ".m4a", context.cacheDir).also { it.writeBytes(audio.data) }
        val outputs = output.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val preferredOutput = outputs.firstOrNull(::isPrivateOutput)
            ?: if (allowSpeaker) outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER } else null
        val media = MediaPlayer().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            setDataSource(target.absolutePath)
            preferredOutput?.let { setPreferredDevice(it) }
            setOnCompletionListener { onFinished(); stop() }
            prepare()
            start()
        }
        player = media
        temporaryFile = target
        if (!allowSpeaker) {
            callback = object : AudioDeviceCallback() {
                override fun onAudioDevicesRemoved(removedDevices: Array<AudioDeviceInfo>) {
                    if (!hasPrivateHeadphones(context)) stop()
                }
            }.also { callback -> output.registerAudioDeviceCallback(callback, null); callbackManager = output }
        }
        true
    }

    private fun daoMark(dao: TouchDao, id: String) {
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launchSafely { dao.markAudioPlayed(id, System.currentTimeMillis()) }
    }

    fun stop() {
        player?.runCatching { stop() }
        player?.release()
        player = null
        temporaryFile?.delete()
        temporaryFile = null
        callback?.let { callbackManager?.unregisterAudioDeviceCallback(it) }
        callback = null
        callbackManager = null
    }
}

private fun kotlinx.coroutines.CoroutineScope.launchSafely(block: suspend () -> Unit) = launch { runCatching { block() } }
