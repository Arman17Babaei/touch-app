package app.touch.wear

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
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import app.touch.communication.CommunicationSettings
import app.touch.communication.AudioAttachment
import app.touch.communication.RecordedAudioCapture
import app.touch.communication.InboxTouch
import app.touch.communication.LiveStatus
import app.touch.communication.TouchCommunication
import app.touch.communication.TouchNotificationIntents
import app.touch.core.AndroidTouchPlayer
import app.touch.core.Touch
import app.touch.core.TouchRecorder
import app.touch.core.amplitudeAt
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
        val debugSetup = if (BuildConfig.DEBUG) intent.debugSetup() else null
        incomingInvite.value = TouchNotificationIntents.liveInvite(intent)
        setContent { TouchWearApp(communication, debugSetup, incomingInvite) }
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
}

private data class DebugSetup(val backend: String, val username: String, val peer: String)

private fun android.content.Intent.debugSetup(): DebugSetup? {
    val backend = getStringExtra("touch.backend") ?: return null
    val username = getStringExtra("touch.username") ?: return null
    val peer = getStringExtra("touch.peer") ?: return null
    return DebugSetup(backend, username, peer)
}

private val Charcoal = Color(0xFF101713)
private val Coral = Color(0xFFFF6B45)
private val Mint = Color(0xFFBAE8D0)
private enum class Screen { RECORDER, LIVE, INBOX, SETUP }

@Composable
private fun TouchWearApp(
    communication: TouchCommunication,
    debugSetup: DebugSetup?,
    incomingInvite: MutableStateFlow<app.touch.communication.LiveInvite?>,
) {
    MaterialTheme {
        val settings by communication.settings.collectAsState(initial = CommunicationSettings())
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
                screen = if (settings.isConfigured) Screen.RECORDER else Screen.SETUP
            }
        }
        LaunchedEffect(invite) {
            invite?.let {
                communication.prepareIncomingLive(it.callId, it.callerUsername)
                liveIncoming = true
                screen = Screen.LIVE
                incomingInvite.value = null
            }
        }
        Box(Modifier.fillMaxSize().systemGestureExclusion().background(Charcoal), contentAlignment = Alignment.Center) {
            when (screen) {
                Screen.SETUP -> SetupScreen(settings, communication) { screen = Screen.RECORDER }
                Screen.INBOX -> InboxScreen(communication) { screen = Screen.RECORDER }
                Screen.LIVE -> LiveScreen(communication, autoStart = !liveIncoming) { screen = Screen.RECORDER }
                Screen.RECORDER -> RecorderScreen(communication, { liveIncoming = false; screen = Screen.LIVE }, { screen = Screen.INBOX }, { screen = Screen.SETUP })
                null -> Text("Starting…")
            }
        }
    }
}

@Composable
private fun SetupScreen(settings: CommunicationSettings, communication: TouchCommunication, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var backend by remember(settings.backendUrl) { mutableStateOf(settings.backendUrl.ifBlank { "http://192.168.1.2:8080" }) }
    var username by remember(settings.username) { mutableStateOf(settings.username) }
    var peer by remember(settings.peerUsername) { mutableStateOf(settings.peerUsername) }
    var error by remember { mutableStateOf(false) }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center, modifier = Modifier.fillMaxSize().padding(horizontal = 22.dp)) {
        Text("CONNECT", color = Coral, fontSize = 15.sp)
        WearInput(backend, { backend = it }, "Backend URL")
        WearInput(username, { username = it }, "My username")
        WearInput(peer, { peer = it }, "Peer username")
        if (error) Text("Check all values", color = Coral, fontSize = 10.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (settings.isConfigured) Text("Cancel", fontSize = 11.sp, modifier = Modifier.clickable(onClick = onDone).padding(7.dp))
            Button(onClick = {
                scope.launch {
                    error = runCatching { communication.configure(backend, username, peer) }.isFailure
                    if (!error) onDone()
                }
            }, modifier = Modifier.size(42.dp), colors = ButtonDefaults.buttonColors(backgroundColor = Coral)) { Text("SAVE", fontSize = 9.sp) }
        }
    }
}

@Composable
private fun WearInput(value: String, onValueChange: (String) -> Unit, hint: String) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = TextStyle(color = Charcoal, fontSize = 11.sp),
        decorationBox = { field ->
            Box(Modifier.fillMaxWidth().height(34.dp).padding(vertical = 3.dp).background(Color.White.copy(alpha = .88f), RoundedCornerShape(9.dp)).padding(horizontal = 8.dp), contentAlignment = Alignment.CenterStart) {
                if (value.isBlank()) Text(hint, color = Charcoal.copy(alpha = .5f), fontSize = 10.sp)
                field()
            }
        },
    )
}

@Composable
private fun RecorderScreen(communication: TouchCommunication, onLive: () -> Unit, onInbox: () -> Unit, onSetup: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val recorder = remember(communication) { TouchRecorder() }
    val player = remember { AndroidTouchPlayer(context.applicationContext) }
    val audioCapture = remember { RecordedAudioCapture(context.applicationContext) }
    val outbox by communication.outbox.collectAsState(initial = emptyList())
    var recording by remember { mutableStateOf(false) }
    var elapsed by remember { mutableIntStateOf(0) }
    var touch by remember { mutableStateOf<Touch?>(null) }
    var sentId by remember { mutableStateOf<String?>(null) }
    var audio by remember { mutableStateOf<AudioAttachment?>(null) }
    var microphoneEnabled by remember { mutableStateOf(false) }

    fun finish(includePartial: Boolean) {
        // The sampling effect and the STOP click can finish on adjacent main-loop
        // turns. Never let the second completion overwrite the captured AAC with null.
        if (!recorder.isRecording && !recording) return
        if (includePartial && recorder.isRecording) recorder.sample()
        touch = recorder.stop()
        audio = audioCapture.stop()
        microphoneEnabled = false
        elapsed = touch?.durationMillis?.toInt() ?: 0
        recording = false
        sentId = null
        communication.setInteractionBusy(false)
    }

    LaunchedEffect(recording) {
        if (!recording) return@LaunchedEffect
        while (recording) {
            delay(recorder.samplePeriodMillis.toLong())
            val more = recorder.sample()
            elapsed = recorder.elapsedMillis
            if (!more) finish(false)
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            player.cancel()
            audioCapture.cancel()
            communication.setInteractionBusy(false)
        }
    }
    val sendState = sentId?.let { id -> outbox.firstOrNull { it.clientMessageId == id }?.status?.name?.lowercase() }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxSize().padding(9.dp).background(Brush.verticalGradient(listOf(Coral, Mint, Color(0xFF26342C))), CircleShape),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.height(96.dp).fillMaxWidth().pointerInput(recorder) {
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
        }) { Text(if (recording) "TOUCH" else sendState ?: formatDuration(elapsed)) }

        if (recording) {
            Text(if (microphoneEnabled) "MIC ON" else "MIC OFF", fontSize = 9.sp, modifier = Modifier.clickable { microphoneEnabled = !microphoneEnabled; audioCapture.setMicrophoneEnabled(microphoneEnabled) }.padding(4.dp))
            Button(onClick = { finish(true) }, colors = ButtonDefaults.buttonColors(backgroundColor = Charcoal), modifier = Modifier.size(52.dp)) { Text("STOP", fontSize = 10.sp) }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = {
                    player.cancel()
                    communication.setInteractionBusy(true)
                    recorder.start()
                    audio = null
                    val permitted = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                    microphoneEnabled = audioCapture.start(permitted)
                    if (!permitted) (context as? MainActivity)?.requestMicrophonePermission()
                    elapsed = 0
                    recording = true
                }, colors = ButtonDefaults.buttonColors(backgroundColor = Coral), modifier = Modifier.size(45.dp)) { Text("REC", fontSize = 9.sp) }
                Button(onClick = {
                    touch?.let { recorded -> scope.launch {
                        communication.setInteractionBusy(true)
                        try {
                            if (player.play(recorded)) delay(recorded.durationMillis + 100)
                            communication.previewAudio(audio, allowSpeaker = true)
                        } finally { communication.setInteractionBusy(false) }
                    } }
                }, enabled = touch?.isSilent == false, colors = ButtonDefaults.buttonColors(backgroundColor = Charcoal), modifier = Modifier.size(45.dp)) { Text("PLAY", fontSize = 8.sp) }
                Button(onClick = { touch?.let { scope.launch { sentId = communication.send(it, audio) } } }, enabled = touch?.isSilent == false, colors = ButtonDefaults.buttonColors(backgroundColor = Charcoal), modifier = Modifier.size(45.dp)) { Text("SEND", fontSize = 8.sp) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(top = 3.dp)) {
                Text("Inbox", fontSize = 10.sp, modifier = Modifier.clickable(onClick = onInbox).padding(4.dp))
                Text("Setup", fontSize = 10.sp, modifier = Modifier.clickable(onClick = onSetup).padding(4.dp))
                Text("Live", fontSize = 10.sp, modifier = Modifier.clickable(onClick = onLive).padding(4.dp))
            }
        }
    }
}

@Composable
private fun LiveScreen(communication: TouchCommunication, autoStart: Boolean, onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val live by communication.liveState.collectAsState()
    val audio by communication.liveAudioState.collectAsState()
    var elapsed by remember { mutableIntStateOf(0) }
    LaunchedEffect(autoStart) { if (autoStart) communication.startLive() }
    LaunchedEffect(live.connectedAtMs) {
        val connectedAt = live.connectedAtMs ?: return@LaunchedEffect
        while (true) {
            elapsed = (System.currentTimeMillis() - connectedAt).coerceAtLeast(0).toInt()
            delay(250)
        }
    }
    DisposableEffect(Unit) { onDispose { communication.setLiveAmplitude(0) } }
    val connected = live.status == LiveStatus.CONNECTED
    val label = when (live.status) {
        LiveStatus.OFF -> "READY"
        LiveStatus.CONNECTING -> "CONNECTING…"
        LiveStatus.RINGING -> "RINGING…"
        LiveStatus.INCOMING -> "${live.peerUsername}\nIS CALLING"
        LiveStatus.CONNECTED -> "CONNECTED\n${formatDuration(elapsed)}"
        LiveStatus.RECONNECTING -> "RECONNECTING…"
        LiveStatus.ENDED -> "CALL ENDED"
        LiveStatus.ERROR -> "CALL FAILED"
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxSize().padding(10.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxWidth().height(100.dp)
                .background(Brush.verticalGradient(listOf(Coral, Mint, Color(0xFF26342C))), CircleShape)
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
        ) { Text(if (connected) "TOUCH" else label, fontSize = 11.sp) }
        if (connected) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (audio.microphoneEnabled) "MIC ON" else "MIC OFF", fontSize = 9.sp, modifier = Modifier.clickable { communication.setLiveMicrophoneEnabled(!audio.microphoneEnabled) }.padding(5.dp))
            Text(if (audio.outputEnabled) if (audio.privateRoute) "HEADPHONES" else "SPEAKER" else "PLAY AUDIO", fontSize = 9.sp, modifier = Modifier.clickable { communication.setLiveAudioOutputEnabled(!audio.outputEnabled) }.padding(5.dp))
        }
        when (live.status) {
            LiveStatus.INCOMING -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = communication::declineLive, modifier = Modifier.size(48.dp), colors = ButtonDefaults.buttonColors(backgroundColor = Charcoal)) { Text("NO", fontSize = 9.sp) }
                Button(onClick = { if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) (context as? MainActivity)?.requestMicrophonePermission(); communication.acceptLive() }, modifier = Modifier.size(48.dp), colors = ButtonDefaults.buttonColors(backgroundColor = Coral)) { Text("YES", fontSize = 9.sp) }
            }
            LiveStatus.CONNECTING, LiveStatus.RINGING, LiveStatus.CONNECTED, LiveStatus.RECONNECTING ->
                Button(onClick = communication::stopLive, modifier = Modifier.size(52.dp), colors = ButtonDefaults.buttonColors(backgroundColor = Coral)) { Text("END", fontSize = 9.sp) }
            LiveStatus.ENDED, LiveStatus.ERROR, LiveStatus.OFF -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Back", modifier = Modifier.clickable(onClick = onBack).padding(10.dp), fontSize = 10.sp)
                Text("Call again", color = Coral, modifier = Modifier.clickable { if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) (context as? MainActivity)?.requestMicrophonePermission(); communication.startLive() }.padding(10.dp), fontSize = 10.sp)
            }
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
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center, modifier = Modifier.fillMaxSize().padding(24.dp)) {
            Text("Clear all touches?", fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Cancel", modifier = Modifier.clickable { confirmingClear = false }.padding(10.dp), fontSize = 10.sp)
                Text("Clear", color = Coral, modifier = Modifier.clickable { confirmingClear = false; scope.launch { communication.clearInbox() } }.padding(10.dp), fontSize = 10.sp)
            }
        }
        return
    }
    ScalingLazyColumn(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        item { Text("INBOX", color = Coral) }
        item { Text("Clear", color = if (inbox.isEmpty()) Color.Gray else Coral, modifier = Modifier.clickable(enabled = inbox.isNotEmpty()) { confirmingClear = true }.padding(6.dp), fontSize = 10.sp) }
        if (inbox.isEmpty()) item { Text("No touches", fontSize = 11.sp) }
        items(inbox, key = InboxTouch::id) { item ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth(.9f).background(Color(0xFF26342C), RoundedCornerShape(14.dp)).padding(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(item.senderUsername, fontSize = 12.sp)
                    Text("${formatDuration(item.touch.durationMillis.toInt())} · ${if (item.playedAt == null) "new" else "played"}", fontSize = 9.sp, color = Color.White.copy(alpha = .65f))
                }
                Button(onClick = { scope.launch { communication.play(item) } }, modifier = Modifier.size(38.dp)) { Text("▶", fontSize = 12.sp) }
				item.audio?.let { Button(onClick = {
					if (playingAudioId == item.id) { communication.stopAudio(); playingAudioId = null }
					else scope.launch { if (communication.playAudio(item, allowSpeaker = true)) playingAudioId = item.id }
				}, modifier = Modifier.size(38.dp)) { Text(if (playingAudioId == item.id) "×" else "♪", fontSize = 12.sp) } }
            }
        }
        item { Text("Back", color = Mint, modifier = Modifier.clickable(onClick = onBack).padding(10.dp)) }
    }
}

private fun formatDuration(milliseconds: Int): String = "%d.%02d s".format(milliseconds / 1_000, (milliseconds % 1_000) / 10)
