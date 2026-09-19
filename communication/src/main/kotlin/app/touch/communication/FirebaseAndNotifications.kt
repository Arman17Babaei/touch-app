package app.touch.communication

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class TouchFirebaseMessagingService : FirebaseMessagingService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        scope.launch {
            TouchCommunication.get(applicationContext).settingsStore.saveFcmToken(token)
            CommunicationWork.enqueueRegistration(applicationContext)
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val deliveryId=message.data["deliveryId"]
        deliveryId?.let { id -> scope.launch { val c=TouchCommunication.get(applicationContext);runCatching{c.api.notificationEvent(c.settingsStore.current(),id,"received")} } }
        when (message.data["type"]) {
            "touch_available" -> CommunicationWork.enqueueSync(applicationContext,deliveryId)
            "live_invite" -> {
                val callId = message.data["callId"] ?: return
                val caller = message.data["callerUsername"] ?: return
                LiveCallService.startIncoming(applicationContext,callId,caller,deliveryId)
                deliveryId?.let{id->scope.launch{val c=TouchCommunication.get(applicationContext);runCatching{c.api.notificationEvent(c.settingsStore.current(),id,"notification_presented")}}}
            }
            "push_test" -> message.data["testId"]?.let { id -> scope.launch { val c=TouchCommunication.get(applicationContext);runCatching{c.api.ackPushTest(c.settingsStore.current(),id)} } }
        }
    }

    override fun onDeletedMessages() { val c=TouchCommunication.get(applicationContext);c.recordDiagnostic("warn","notification","fcm_messages_deleted");CommunicationWork.enqueueSync(applicationContext) }
}

data class LiveInvite(val callId: String, val callerUsername: String)

object TouchNotificationIntents {
    const val EXTRA_LIVE_CALL_ID = "touch.live.callId"
    const val EXTRA_LIVE_CALLER = "touch.live.callerUsername"

    fun liveInvite(intent: Intent?): LiveInvite? {
        val callId = intent?.getStringExtra(EXTRA_LIVE_CALL_ID) ?: return null
        val caller = intent.getStringExtra(EXTRA_LIVE_CALLER) ?: return null
        return LiveInvite(callId, caller)
    }
}

internal object TouchNotifications {
    private const val CHANNEL_ID = "received-touches"
    private const val LIVE_CHANNEL_ID = "live-touch-calls"

    fun show(context: Context, count: Int) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Received touches", NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val pendingIntent = launchIntent?.let {
            PendingIntent.getActivity(
                context,
                0,
                it.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(context.applicationInfo.icon)
            .setContentTitle(if (count == 1) "New touch" else "$count new touches")
            .setContentText("Open Touch to view or replay")
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        manager.notify(1001, notification)
    }

    fun showLiveInvite(context: Context, callId: String, callerUsername: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(LIVE_CHANNEL_ID, "Live touch calls", NotificationManager.IMPORTANCE_HIGH),
            )
        }
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(TouchNotificationIntents.EXTRA_LIVE_CALL_ID, callId)
            putExtra(TouchNotificationIntents.EXTRA_LIVE_CALLER, callerUsername)
        }
        val pendingIntent = launchIntent?.let {
            PendingIntent.getActivity(
                context,
                callId.hashCode(),
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        val notification = NotificationCompat.Builder(context, LIVE_CHANNEL_ID)
            .setSmallIcon(context.applicationInfo.icon)
            .setContentTitle("Live touch from $callerUsername")
            .setContentText("Open Touch to accept or decline")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        manager.notify(callId.hashCode(), notification)
    }

    fun dismiss(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(1001)
    }
}
