package app.touch.mobile

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.provider.Settings
import android.net.Uri
import android.app.NotificationManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.touch.communication.CommunicationSettings
import app.touch.communication.AudioAttachment
import app.touch.communication.RecordedAudioCapture
import app.touch.communication.InboxTouch
import app.touch.communication.LiveStatus
import app.touch.communication.OutboxStatus
import app.touch.communication.TouchCommunication
import app.touch.communication.TouchNotificationIntents
import app.touch.core.AndroidTouchPlayer
import app.touch.core.Touch
import app.touch.core.TouchRecorder
import app.touch.core.amplitudeAt
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val communication by lazy { TouchCommunication.get(this) }
    private val incomingInvite = MutableStateFlow(TouchNotificationIntents.liveInvite(intent))
    private val syncHandler = Handler(Looper.getMainLooper())
    private val audioManager by lazy { getSystemService(AudioManager::class.java) }
    private val audioDevices = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<AudioDeviceInfo>) = communication.onAudioRouteChanged()
        override fun onAudioDevicesRemoved(removedDevices: Array<AudioDeviceInfo>) = communication.onAudioRouteChanged()
    }
    private val syncInbox = object : Runnable {
        override fun run() {
            communication.syncInbox()
            syncHandler.postDelayed(this, 10_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }
        enableEdgeToEdge()
        val debugSetup = if (BuildConfig.DEBUG) intent.debugSetup() else null
        incomingInvite.value = TouchNotificationIntents.liveInvite(intent)
        setContent { TouchMobileApp(communication, debugSetup, incomingInvite) }
    }

    override fun onResume() {
        super.onResume()
        communication.onForeground()
        audioManager.registerAudioDeviceCallback(audioDevices, null)
        syncHandler.post(syncInbox)
    }

    override fun onPause() {
        syncHandler.removeCallbacks(syncInbox)
        audioManager.unregisterAudioDeviceCallback(audioDevices)
        communication.onBackground()
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incomingInvite.value = TouchNotificationIntents.liveInvite(intent)
    }

    fun requestMicrophonePermission() = requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 101)
    fun requestFullScreenCalls(){if(Build.VERSION.SDK_INT>=34&&!getSystemService(NotificationManager::class.java).canUseFullScreenIntent())startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,Uri.parse("package:$packageName")))}
}

class IncomingCallActivity:ComponentActivity(){
    override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);setShowWhenLocked(true);setTurnScreenOn(true);app.touch.communication.LiveCallService.command(this,app.touch.communication.LiveCallService.ACTION_OPENED);val caller=intent.getStringExtra(app.touch.communication.LiveCallService.EXTRA_CALLER).orEmpty();val callId=intent.getStringExtra(app.touch.communication.LiveCallService.EXTRA_CALL_ID);setContent{MaterialTheme{Surface(color=Paper,modifier=Modifier.fillMaxSize()){Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center,modifier=Modifier.padding(24.dp)){Text("Live touch from",fontSize=18.sp);Text(caller,fontSize=32.sp,color=Signal);Spacer(Modifier.height(24.dp));Row(horizontalArrangement=Arrangement.spacedBy(16.dp)){Button(onClick={app.touch.communication.LiveCallService.command(this@IncomingCallActivity,app.touch.communication.LiveCallService.ACTION_DECLINE);finish()},colors=ButtonDefaults.buttonColors(containerColor=Ink)){Text("Decline")};Button(onClick={if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO),101);app.touch.communication.LiveCallService.command(this@IncomingCallActivity,app.touch.communication.LiveCallService.ACTION_ACCEPT);packageManager.getLaunchIntentForPackage(packageName)?.putExtra(TouchNotificationIntents.EXTRA_LIVE_CALL_ID,callId)?.putExtra(TouchNotificationIntents.EXTRA_LIVE_CALLER,caller)?.let(::startActivity);finish()},colors=ButtonDefaults.buttonColors(containerColor=Signal)){Text("Answer")}}}}}}}
}

private data class DebugSetup(val backend: String, val username: String, val peer: String)

private fun android.content.Intent.debugSetup(): DebugSetup? {
    val backend = getStringExtra("touch.backend") ?: return null
    val username = getStringExtra("touch.username") ?: return null
    val peer = getStringExtra("touch.peer") ?: return null
    return DebugSetup(backend, username, peer)
}

private val Ink = Color(0xFF17201B)
private val Paper = Color(0xFFF3EFE4)
private val Signal = Color(0xFFFF5A36)
private val Mint = Color(0xFFB9E6CF)
private enum class Screen { RECORDER, LIVE, INBOX, SETUP }

@Composable
private fun TouchMobileApp(
    communication: TouchCommunication,
    debugSetup: DebugSetup?,
    incomingInvite: MutableStateFlow<app.touch.communication.LiveInvite?>,
) {
    MaterialTheme {
        val settings by communication.settings.collectAsState(initial = CommunicationSettings())
        val currentLive by communication.liveState.collectAsState()
        var screen by remember { mutableStateOf<Screen?>(null) }
        var liveIncoming by remember { mutableStateOf(false) }
        val invite by incomingInvite.collectAsState()
        LaunchedEffect(Unit) {
            if (debugSetup == null) communication.initialize()
            else {
                communication.configure(debugSetup.backend, debugSetup.username, debugSetup.peer)
                screen = Screen.RECORDER
            }
        }
        LaunchedEffect(settings) {
            if (screen == null && settings.installationId.isNotBlank()) {
                screen = if (!settings.isConfigured) Screen.SETUP else if(currentLive.status in setOf(LiveStatus.CONNECTING,LiveStatus.RINGING,LiveStatus.INCOMING,LiveStatus.CONNECTED,LiveStatus.RECONNECTING))Screen.LIVE else Screen.RECORDER
            }
        }
        LaunchedEffect(invite) {
            invite?.let {
                if(communication.liveState.value.callId!=it.callId)communication.prepareIncomingLive(it.callId, it.callerUsername)
                liveIncoming = true
                screen = Screen.LIVE
                incomingInvite.value = null
            }
        }
        Surface(color = Paper, modifier = Modifier.fillMaxSize()) {
            when (screen) {
                Screen.SETUP -> SetupScreen(settings, communication) { screen = Screen.RECORDER }
                Screen.INBOX -> InboxScreen(communication) { screen = Screen.RECORDER }
                Screen.LIVE -> LiveScreen(communication, autoStart = !liveIncoming && currentLive.status !in setOf(LiveStatus.CONNECTING,LiveStatus.RINGING,LiveStatus.INCOMING,LiveStatus.CONNECTED,LiveStatus.RECONNECTING)) { screen = Screen.RECORDER }
                Screen.RECORDER -> RecorderScreen(communication, settings.username, { liveIncoming = false; screen = Screen.LIVE }, { screen = Screen.INBOX }, { screen = Screen.SETUP })
                null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Starting…") }
            }
        }
    }
}

@Composable
private fun SetupScreen(settings: CommunicationSettings, communication: TouchCommunication, onBack: () -> Unit) {
    val context=androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val contacts by communication.contacts.collectAsState()
    val serverStatus by communication.installationStatus.collectAsState()
    var backend by remember(settings.backendUrl) { mutableStateOf(settings.backendUrl.ifBlank { "http://192.168.1.2:8080" }) }
    var username by remember(settings.username) { mutableStateOf(settings.username) }
    var peer by remember(settings.peerUsername) { mutableStateOf(settings.peerUsername) }
    var error by remember { mutableStateOf<String?>(null) }
    var pushStatus by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.Center, modifier = Modifier.fillMaxSize().statusBarsPadding().padding(24.dp)) {
        Text("Connect Touch", fontSize = 28.sp, color = Ink)
        Text("Register this device, then add or choose recent contacts.", color = Ink.copy(alpha = .65f))
        Spacer(Modifier.height(18.dp))
        OutlinedTextField(backend, { backend = it }, label = { Text("LAN backend URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(username, { username = it }, label = { Text("This username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(peer, { peer = it }, label = { Text("Contact username (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        serverStatus?.let { Text("Server: ${it.username} · FCM ${if(it.fcmTokenPresent) it.fcmTokenFingerprint ?: "registered" else "missing"}",fontSize=12.sp,color=Ink.copy(alpha=.65f)) }
        contacts.take(5).forEach { contact -> Row(verticalAlignment=Alignment.CenterVertically){TextButton(onClick={scope.launch{communication.selectContact(contact.username);peer=contact.username}}){Text(if(contact.username==settings.peerUsername)"✓ ${contact.username}" else contact.username)};if(contact.saved)TextButton(onClick={scope.launch{communication.removeContact(contact.username)}}){Text("Remove")}} }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (settings.isConfigured) TextButton(onClick = onBack) { Text("Cancel") }
            Button(onClick = {
                scope.launch {
                    error = runCatching { communication.configure(backend, username, peer) }.exceptionOrNull()?.message
                    if(error==null&&peer.isNotBlank())error=runCatching{communication.addContact(peer)}.exceptionOrNull()?.message
                    if (error == null) onBack()
                }
            }) { Text("Save") }
            if(settings.isConfigured)Button(onClick={scope.launch{runCatching{communication.verifyNotifications{pushStatus=it.status}}.onFailure{pushStatus=it.message}}}){Text("Verify notifications")}
            if(Build.VERSION.SDK_INT>=34)TextButton(onClick={ (context as? MainActivity)?.requestFullScreenCalls() }){Text("Enable call overlays")}
        }
        pushStatus?.let{Text("Push test: $it",fontSize=12.sp,color=Ink.copy(alpha=.65f))}
    }
}

@Composable
private fun RecorderScreen(
    communication: TouchCommunication,
    username: String,
    onLive: () -> Unit,
    onInbox: () -> Unit,
    onSetup: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val recorder = remember(communication) { TouchRecorder() }
    val player = remember { AndroidTouchPlayer(context.applicationContext) }
    val audioCapture = remember { RecordedAudioCapture(context.applicationContext) }
    val outbox by communication.outbox.collectAsState(initial = emptyList())
    val settings by communication.settings.collectAsState(initial=CommunicationSettings())
    val contacts by communication.contacts.collectAsState()
    var recording by remember { mutableStateOf(false) }
    var elapsedMillis by remember { mutableIntStateOf(0) }
    var touch by remember { mutableStateOf<Touch?>(null) }
    var sentId by remember { mutableStateOf<String?>(null) }
    var audio by remember { mutableStateOf<AudioAttachment?>(null) }
    var microphoneEnabled by remember { mutableStateOf(false) }

    fun finishRecording(capturePartialInterval: Boolean) {
        if (!recorder.isRecording && !recording) return
        if (capturePartialInterval && recorder.isRecording) recorder.sample()
        touch = recorder.stop()
        audio = audioCapture.stop()
        microphoneEnabled = false
        elapsedMillis = touch?.durationMillis?.toInt() ?: 0
        recording = false
        communication.setInteractionBusy(false)
        sentId = null
    }

    LaunchedEffect(recording) {
        if (!recording) return@LaunchedEffect
        while (recording) {
            delay(recorder.samplePeriodMillis.toLong())
            val canContinue = recorder.sample()
            elapsedMillis = recorder.elapsedMillis
            if (!canContinue) finishRecording(false)
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            player.cancel()
            audioCapture.cancel()
            communication.setInteractionBusy(false)
        }
    }
    val sendStatus = sentId?.let { id -> outbox.firstOrNull { it.clientMessageId == id }?.status }

    Column(modifier = Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column {
                Text("TOUCH", color = Ink, fontSize = 30.sp)
                Text(username, color = Ink.copy(alpha = .55f), fontSize = 12.sp)
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onInbox) { Text("Inbox") }
            TextButton(onClick = onSetup) { Text("Setup") }
            Text(formatDuration(elapsedMillis), color = Ink.copy(alpha = .65f), fontSize = 16.sp)
        }
        Text(if (recording) "Move higher for a stronger pulse" else "Draw a vibration with your fingertips", color = Ink.copy(alpha = .65f), fontSize = 14.sp)
        Row(horizontalArrangement=Arrangement.spacedBy(4.dp),modifier=Modifier.fillMaxWidth()){contacts.take(4).forEach{contact->TextButton(onClick={scope.launch{communication.selectContact(contact.username)}}){Text(if(contact.username==settings.peerUsername)"✓ ${contact.username}" else contact.username,fontSize=12.sp)}}}
        Spacer(Modifier.height(16.dp))
        RecordingPad(recorder, recording, if (recording) null else touch, Modifier.weight(1f))
        sendStatus?.let { Text("Send: ${it.name.lowercase()}", color = Ink.copy(alpha = .65f), modifier = Modifier.padding(top = 8.dp)) }
        Spacer(Modifier.height(12.dp))
        if (recording) {
            TextButton(onClick = { microphoneEnabled = !microphoneEnabled; audioCapture.setMicrophoneEnabled(microphoneEnabled) }) { Text(if (microphoneEnabled) "Mic on" else "Mic off") }
            Button(onClick = { finishRecording(true) }, colors = ButtonDefaults.buttonColors(containerColor = Ink), modifier = Modifier.fillMaxWidth()) { Text("Stop recording") }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = {
                    player.cancel()
                    communication.setInteractionBusy(true)
                    recorder.start()
                    audio = null
                    val permitted = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                    microphoneEnabled = audioCapture.start(permitted)
                    if (!permitted) (context as? MainActivity)?.requestMicrophonePermission()
                    elapsedMillis = 0
                    recording = true
                }, colors = ButtonDefaults.buttonColors(containerColor = Signal), modifier = Modifier.weight(1f)) { Text(if (touch == null) "Record" else "Again") }
                Button(onClick = {
                    touch?.let { recorded ->
                        scope.launch {
                            communication.setInteractionBusy(true)
                            try {
                            if (player.play(recorded)) delay(recorded.durationMillis + 100)
                            communication.previewAudio(audio, allowSpeaker = true)
                            } finally { communication.setInteractionBusy(false) }
                        }
                    }
                }, enabled = touch?.isSilent == false, colors = ButtonDefaults.buttonColors(containerColor = Ink), modifier = Modifier.width(92.dp)) { Text("Play") }
                Button(onClick = { touch?.let { scope.launch { sentId = communication.send(it, audio) } } }, enabled = touch?.isSilent == false && sendStatus != OutboxStatus.SENDING && settings.peerUsername.isNotBlank(), modifier = Modifier.width(92.dp)) { Text("Send") }
            }
            TextButton(onClick = onLive) { Text("Go live") }
        }
    }
}

@Composable
private fun LiveScreen(communication: TouchCommunication, autoStart: Boolean, onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val live by communication.liveState.collectAsState()
    val audio by communication.liveAudioState.collectAsState()
    val diagnostics by communication.liveDiagnostics.collectAsState()
    var details by remember{mutableStateOf(false)}
    var elapsed by remember { mutableIntStateOf(0) }
    // Start once when the user enters Live. Do not restart after hangup changes
    // the derived autoStart value from false back to true.
    LaunchedEffect(Unit) { if (autoStart) communication.startLive() }
    LaunchedEffect(live.connectedAtMs) {
        val connectedAt = live.connectedAtMs ?: return@LaunchedEffect
        while (true) {
            elapsed = (System.currentTimeMillis() - connectedAt).coerceAtLeast(0).toInt()
            delay(250)
        }
    }
    DisposableEffect(Unit) { onDispose { communication.setLiveAmplitude(0) } }
    val connected = live.status == LiveStatus.CONNECTED
    val statusText = when (live.status) {
        LiveStatus.OFF -> "Ready"
        LiveStatus.CONNECTING -> "Connecting…"
        LiveStatus.RINGING -> "Ringing ${live.peerUsername}…"
        LiveStatus.INCOMING -> "Live touch from ${live.peerUsername}"
        LiveStatus.CONNECTED -> "Connected · ${formatDuration(elapsed)}"
        LiveStatus.RECONNECTING -> "Reconnecting…"
        LiveStatus.ENDED -> when (live.reason) {
            "declined" -> "Call declined"
            "unanswered" -> "No answer"
            "connection_lost" -> "Connection lost"
            else -> "Call ended"
        }
        LiveStatus.ERROR -> live.reason ?: "Live call failed"
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("LIVE", color = Signal, fontSize = 28.sp)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = {
                if (live.status in setOf(LiveStatus.CONNECTING, LiveStatus.RINGING, LiveStatus.INCOMING, LiveStatus.CONNECTED, LiveStatus.RECONNECTING)) communication.stopLive()
                onBack()
            }) { Text("Back") }
        }
        Text(statusText, color = Ink.copy(alpha = .7f))
        Text("Peer ${live.peerUsername.ifBlank{"—"}} · invite ${live.notificationStatus.ifBlank{"—"}} · generation ${live.generation} · route ${if(audio.outputEnabled)if(audio.privateRoute)"headphones" else "speaker" else "muted"}",fontSize=12.sp,color=Ink.copy(alpha=.6f))
        TextButton(onClick={details=!details}){Text(if(details)"Hide diagnostics" else "Show diagnostics")}
        if(details)Text("Haptics buffer ${diagnostics.bufferedMillis} ms, underruns ${diagnostics.underruns}, gaps ${diagnostics.outOfOrderBatches}. Audio encoded ${audio.encodedFrames}, decoded ${audio.decodedFrames}, dropped ${audio.droppedFrames}, decoder errors ${audio.decoderErrors}",fontSize=11.sp,color=Ink.copy(alpha=.6f))
        Spacer(Modifier.height(16.dp))
        Box(
            Modifier.weight(1f).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Signal, Mint, Color(0xFFE9E5DA))), RoundedCornerShape(28.dp))
                .pointerInput(connected) {
                    if (!connected) return@pointerInput
                    try {
                        awaitEachGesture {
                            do {
                                val event = awaitPointerEvent()
                                val pressed = event.changes.filter { it.pressed }
                                communication.setLiveAmplitude(
                                    pressed.maxOfOrNull { amplitudeAt(it.position.y, size.height.toFloat()) } ?: 0,
                                )
                                event.changes.forEach { it.consume() }
                            } while (event.changes.any { it.pressed })
                            communication.setLiveAmplitude(0)
                        }
                    } finally { communication.setLiveAmplitude(0) }
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(if (connected) "TOUCH HERE" else statusText, color = Ink.copy(alpha = .65f), fontSize = 16.sp)
        }
        Spacer(Modifier.height(14.dp))
        if (connected) Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = { communication.setLiveMicrophoneEnabled(!audio.microphoneEnabled) }, modifier = Modifier.weight(1f)) { Text(if (audio.microphoneEnabled) "Mic on" else "Mic off") }
            Button(onClick = { communication.setLiveAudioOutputEnabled(!audio.outputEnabled) }, modifier = Modifier.weight(1f)) { Text(if (audio.outputEnabled) if (audio.privateRoute) "Headphones" else "Speaker" else "Play audio") }
        }
        when (live.status) {
            LiveStatus.INCOMING -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = communication::declineLive, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Ink)) { Text("Decline") }
                Button(onClick = { if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) (context as? MainActivity)?.requestMicrophonePermission(); communication.acceptLive() }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Signal)) { Text("Accept") }
            }
            LiveStatus.CONNECTING, LiveStatus.RINGING, LiveStatus.CONNECTED, LiveStatus.RECONNECTING ->
                Button(onClick = communication::stopLive, colors = ButtonDefaults.buttonColors(containerColor = Signal), modifier = Modifier.fillMaxWidth()) { Text("Hang up") }
            LiveStatus.ENDED, LiveStatus.ERROR, LiveStatus.OFF ->
                Button(onClick = { if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) (context as? MainActivity)?.requestMicrophonePermission(); communication.startLive() }, colors = ButtonDefaults.buttonColors(containerColor = Signal), modifier = Modifier.fillMaxWidth()) { Text("Call again") }
        }
    }
}

@Composable
private fun InboxScreen(communication: TouchCommunication, onBack: () -> Unit) {
    val inbox by communication.inbox.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var confirmingClear by remember { mutableStateOf(false) }
    var playingAudioId by remember { mutableStateOf<String?>(null) }
    if (confirmingClear) {
        AlertDialog(
            onDismissRequest = { confirmingClear = false },
            title = { Text("Clear inbox?") },
            text = { Text("This removes all received touches stored on this device.") },
            confirmButton = { TextButton(onClick = { confirmingClear = false; scope.launch { communication.clearInbox() } }) { Text("Clear") } },
            dismissButton = { TextButton(onClick = { confirmingClear = false }) { Text("Cancel") } },
        )
    }
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Inbox", color = Ink, fontSize = 28.sp)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { confirmingClear = true }, enabled = inbox.isNotEmpty()) { Text("Clear") }
            TextButton(onClick = onBack) { Text("Back") }
        }
        if (inbox.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No received touches yet", color = Ink.copy(alpha = .6f)) }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(inbox, key = InboxTouch::id) { item ->
                    Column(modifier = Modifier.fillMaxWidth().background(Mint.copy(alpha = .35f), RoundedCornerShape(16.dp)).padding(14.dp)) {
                        Column {
                            Text(item.senderUsername, color = Ink)
                            Text("${formatDuration(item.touch.durationMillis.toInt())} · ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(item.receivedAt))}", color = Ink.copy(alpha = .6f), fontSize = 12.sp)
                            Text(if (item.playedAt == null) "Not played" else "Played", color = Ink.copy(alpha = .55f), fontSize = 12.sp)
							item.audio?.let { Text(if (item.audioPlayedAt == null) "Voice available — choose Play voice to use the speaker" else "Voice played", color = Ink.copy(alpha = .55f), fontSize = 12.sp) }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                            Button(onClick = { scope.launch { communication.play(item) } }) { Text("Play touch") }
							item.audio?.let { Button(onClick = {
								if (playingAudioId == item.id) { communication.stopAudio(); playingAudioId = null }
								else scope.launch { if (communication.playAudio(item, allowSpeaker = true)) playingAudioId = item.id }
							}) { Text(if (playingAudioId == item.id) "Mute voice" else "Play voice") } }
						}
                    }
                }
            }
        }
    }
}

@Composable
private fun RecordingPad(recorder: TouchRecorder, enabled: Boolean, touch: Touch?, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Signal, Mint, Color(0xFFE9E5DA))), RoundedCornerShape(28.dp)).pointerInput(recorder) {
        try {
            awaitEachGesture {
                do {
                    val event = awaitPointerEvent()
                    if (recorder.isRecording) event.changes.forEach { change ->
                        if (change.pressed) recorder.updatePointer(change.id.value, amplitudeAt(change.position.y, size.height.toFloat())) else recorder.removePointer(change.id.value)
                        change.consume()
                    }
                } while (event.changes.any { it.pressed })
            }
        } finally { recorder.cancelPointers() }
    }) {
        Text("255", color = Color.White.copy(alpha = .8f), modifier = Modifier.align(Alignment.TopStart).padding(18.dp))
        Text(if (enabled) "TOUCH HERE" else if (touch == null) "READY" else "RECORDED", color = Ink.copy(alpha = .6f), fontSize = 13.sp, modifier = Modifier.align(Alignment.Center))
        Text("1", color = Ink.copy(alpha = .45f), modifier = Modifier.align(Alignment.BottomStart).padding(18.dp))
        if (!enabled && touch != null) Canvas(Modifier.fillMaxSize().padding(18.dp)) {
            if (touch.amplitudes.size < 2) return@Canvas
            val step = size.width / (touch.amplitudes.size - 1)
            touch.amplitudes.zipWithNext().forEachIndexed { index, (a, b) ->
                drawLine(Ink.copy(alpha = .7f), Offset(index * step, size.height * (1f - a / 255f)), Offset((index + 1) * step, size.height * (1f - b / 255f)), 2.dp.toPx())
            }
        }
    }
}

private fun formatDuration(milliseconds: Int): String = "%d.%02d s".format(milliseconds / 1_000, (milliseconds % 1_000) / 10)
