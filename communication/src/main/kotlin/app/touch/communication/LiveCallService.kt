package app.touch.communication

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class LiveCallService : Service() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private lateinit var communication:TouchCommunication
    private var deliveryId:String?=null
    private var seenActive=false
    override fun onCreate(){super.onCreate();communication=TouchCommunication.get(this);channel();scope.launch{communication.live.state.collectLatest{state->
        if(state.status in setOf(LiveStatus.CONNECTING,LiveStatus.RINGING,LiveStatus.INCOMING,LiveStatus.CONNECTED,LiveStatus.RECONNECTING))seenActive=true
        if(state.status in setOf(LiveStatus.ENDED,LiveStatus.ERROR)){if(seenActive){stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()}}
        else if(state.status!=LiveStatus.OFF)promote(state)
    }}}
    override fun onBind(intent:Intent?):IBinder?=null
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int{
        val action=intent?.action
        when(action){
            ACTION_OUTGOING->communication.live.startCall()
            ACTION_INCOMING->{deliveryId=intent.getStringExtra(EXTRA_DELIVERY_ID);communication.live.prepareIncoming(intent.getStringExtra(EXTRA_CALL_ID)?:return START_NOT_STICKY,intent.getStringExtra(EXTRA_CALLER)?:return START_NOT_STICKY)}
            ACTION_ACCEPT->{communication.live.accept();reportAction(intent,"answered")}
            ACTION_DECLINE->{communication.live.decline();reportAction(intent,"declined")}
            ACTION_HANGUP->communication.live.hangUp()
            ACTION_OPENED->reportAction(intent,"opened")
        }
        // Answer/Open are dispatched from the full-screen activity. Reposting an
        // INCOMING full-screen notification here can launch a second overlay after
        // the first one finishes; subsequent state emissions update the notification.
        if(action==ACTION_OUTGOING||action==ACTION_INCOMING)promote(communication.live.state.value)
        return START_NOT_STICKY
    }
    private fun reportAction(intent:Intent,event:String){(intent.getStringExtra(EXTRA_DELIVERY_ID)?:deliveryId)?.let{id->scope.launch{runCatching{communication.api.notificationEvent(communication.settingsStore.current(),id,event)}}}}
    private fun promote(state:LiveState){
        val incoming=state.status==LiveStatus.INCOMING
        val notification=buildNotification(state,incoming)
        val type=if(Build.VERSION.SDK_INT>=30){
            var value=ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            if(!incoming&&state.status==LiveStatus.CONNECTED&&hasMicrophonePermission(this))value=value or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            value
        }else 0
        runCatching{ServiceCompat.startForeground(this,NOTIFICATION_ID,notification,type)}.onFailure{communication.recordDiagnostic("error","call","foreground_service_failed",it.message.orEmpty(),state.callId)}
    }
    private fun buildNotification(state:LiveState,incoming:Boolean):Notification{
        val launch=packageManager.getLaunchIntentForPackage(packageName)?.apply{addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP);putExtra(TouchNotificationIntents.EXTRA_LIVE_CALL_ID,state.callId);putExtra(TouchNotificationIntents.EXTRA_LIVE_CALLER,state.peerUsername)}
        val open=PendingIntent.getActivity(this,31,launch,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val overlayIntent=Intent().setClassName(packageName,if(packageManager.hasSystemFeature("android.hardware.type.watch"))"app.touch.wear.IncomingCallActivity" else "app.touch.mobile.IncomingCallActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra(EXTRA_CALLER,state.peerUsername).putExtra(EXTRA_CALL_ID,state.callId)
        val overlay=PendingIntent.getActivity(this,35,overlayIntent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        fun action(action:String,code:Int)=PendingIntent.getService(this,code,Intent(this,LiveCallService::class.java).setAction(action).putExtra(EXTRA_DELIVERY_ID,deliveryId),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val person=Person.Builder().setName(state.peerUsername.ifBlank{"Touch contact"}).build()
        val style=if(incoming)NotificationCompat.CallStyle.forIncomingCall(person,action(ACTION_DECLINE,32),action(ACTION_ACCEPT,33)) else NotificationCompat.CallStyle.forOngoingCall(person,action(ACTION_HANGUP,34))
        return NotificationCompat.Builder(this,CHANNEL_ID).setSmallIcon(applicationInfo.icon).setContentTitle(if(incoming)"Live touch from ${state.peerUsername}" else "Live touch · ${state.peerUsername}").setContentText(state.status.name.lowercase()).setCategory(NotificationCompat.CATEGORY_CALL).setPriority(NotificationCompat.PRIORITY_HIGH).setOngoing(!incoming).setContentIntent(open).setStyle(style).apply{if(incoming)setFullScreenIntent(overlay,true)}.build()
    }
    private fun channel(){if(Build.VERSION.SDK_INT>=26)getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL_ID,"Live touch calls",NotificationManager.IMPORTANCE_HIGH))}
    override fun onDestroy(){scope.cancel();super.onDestroy()}
    companion object{
        const val ACTION_OUTGOING="touch.call.OUTGOING";const val ACTION_INCOMING="touch.call.INCOMING";const val ACTION_ACCEPT="touch.call.ACCEPT";const val ACTION_DECLINE="touch.call.DECLINE";const val ACTION_HANGUP="touch.call.HANGUP";const val ACTION_OPENED="touch.call.OPENED"
        const val EXTRA_CALL_ID="callId";const val EXTRA_CALLER="caller";const val EXTRA_DELIVERY_ID="deliveryId";private const val CHANNEL_ID="live-touch-calls";private const val NOTIFICATION_ID=2001
        private fun start(context:Context,intent:Intent){if(Build.VERSION.SDK_INT>=26)context.startForegroundService(intent)else context.startService(intent)}
        fun startOutgoing(context:Context)=start(context,Intent(context,LiveCallService::class.java).setAction(ACTION_OUTGOING))
        fun startIncoming(context:Context,callId:String,caller:String,deliveryId:String?)=start(context,Intent(context,LiveCallService::class.java).setAction(ACTION_INCOMING).putExtra(EXTRA_CALL_ID,callId).putExtra(EXTRA_CALLER,caller).putExtra(EXTRA_DELIVERY_ID,deliveryId))
        fun command(context:Context,action:String)=start(context,Intent(context,LiveCallService::class.java).setAction(action))
    }
}
