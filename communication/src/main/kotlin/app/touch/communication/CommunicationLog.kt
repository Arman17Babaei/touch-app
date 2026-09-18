package app.touch.communication

import android.util.Log

internal object CommunicationLog {
    private const val TAG = "TouchComm"

    fun debug(message: String) {
        if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, message)
    }

    fun info(message: String) {
        if (Log.isLoggable(TAG, Log.INFO)) Log.i(TAG, message)
    }

    fun warn(message: String, error: Throwable? = null) {
        if (Log.isLoggable(TAG, Log.WARN)) Log.w(TAG, message, error)
    }

    fun error(message: String, error: Throwable? = null) {
        if (Log.isLoggable(TAG, Log.ERROR)) Log.e(TAG, message, error)
    }
}
