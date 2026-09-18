package app.touch.core

import android.content.Context
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

class AndroidTouchPlayer(
    context: Context,
    private val optimizer: TouchOptimizer = TouchOptimizer(),
) {
    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java).defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    fun play(touch: Touch): Boolean {
        if (!vibrator.hasVibrator() || touch.isSilent || touch.amplitudes.isEmpty()) return false
        val waveform = optimizer.optimize(touch, vibrator.hasAmplitudeControl())
        if (waveform.timingsMillis.isEmpty()) return false

        cancel()
        val effect = VibrationEffect.createWaveform(
            waveform.timingsMillis,
            waveform.amplitudes,
            -1,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // This is user-authored content, not brief UI feedback. USAGE_MEDIA avoids having
            // playback muted together with the system's "touch interactions" setting.
            val attributes = VibrationAttributes.createForUsage(VibrationAttributes.USAGE_MEDIA)
            vibrator.vibrate(effect, attributes)
        } else {
            vibrator.vibrate(effect)
        }
        return true
    }

    fun cancel() = vibrator.cancel()
}
