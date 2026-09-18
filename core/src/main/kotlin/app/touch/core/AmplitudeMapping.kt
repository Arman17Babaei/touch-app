package app.touch.core

import kotlin.math.roundToInt

fun amplitudeAt(y: Float, height: Float): Int {
    if (height <= 0f) return 1
    val fractionFromBottom = 1f - (y / height).coerceIn(0f, 1f)
    return (1f + fractionFromBottom * 254f).roundToInt().coerceIn(1, 255)
}
