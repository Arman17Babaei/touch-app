package app.touch.mobile

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import app.touch.communication.InboxTouch
import app.touch.communication.OutboxStatus
import app.touch.communication.TouchCommunication
import app.touch.core.AndroidTouchPlayer
import app.touch.core.Touch
import app.touch.core.TouchRecorder
import app.touch.core.amplitudeAt
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val communication by lazy { TouchCommunication.get(this) }
    private val syncHandler = Handler(Looper.getMainLooper())
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
        setContent { TouchMobileApp(communication, debugSetup) }
    }

    override fun onResume() {
        super.onResume()
        communication.onForeground()
        syncHandler.post(syncInbox)
    }

    override fun onPause() {
        syncHandler.removeCallbacks(syncInbox)
        super.onPause()
    }
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
private enum class Screen { RECORDER, INBOX, SETUP }

@Composable
private fun TouchMobileApp(communication: TouchCommunication, debugSetup: DebugSetup?) {
    MaterialTheme {
        val settings by communication.settings.collectAsState(initial = CommunicationSettings())
        var screen by remember { mutableStateOf<Screen?>(null) }
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
        Surface(color = Paper, modifier = Modifier.fillMaxSize()) {
            when (screen) {
                Screen.SETUP -> SetupScreen(settings, communication) { screen = Screen.RECORDER }
                Screen.INBOX -> InboxScreen(communication) { screen = Screen.RECORDER }
                Screen.RECORDER -> RecorderScreen(communication, settings.username, { screen = Screen.INBOX }, { screen = Screen.SETUP })
                null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Starting…") }
            }
        }
    }
}

@Composable
private fun SetupScreen(settings: CommunicationSettings, communication: TouchCommunication, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var backend by remember(settings.backendUrl) { mutableStateOf(settings.backendUrl.ifBlank { "http://192.168.1.2:8080" }) }
    var username by remember(settings.username) { mutableStateOf(settings.username) }
    var peer by remember(settings.peerUsername) { mutableStateOf(settings.peerUsername) }
    var error by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.Center, modifier = Modifier.fillMaxSize().statusBarsPadding().padding(24.dp)) {
        Text("Connect Touch", fontSize = 28.sp, color = Ink)
        Text("Each installation has its own username and one saved recipient.", color = Ink.copy(alpha = .65f))
        Spacer(Modifier.height(18.dp))
        OutlinedTextField(backend, { backend = it }, label = { Text("LAN backend URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(username, { username = it }, label = { Text("This username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(peer, { peer = it }, label = { Text("Peer username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (settings.isConfigured) TextButton(onClick = onBack) { Text("Cancel") }
            Button(onClick = {
                scope.launch {
                    error = runCatching { communication.configure(backend, username, peer) }.exceptionOrNull()?.message
                    if (error == null) onBack()
                }
            }) { Text("Save") }
        }
    }
}

@Composable
private fun RecorderScreen(
    communication: TouchCommunication,
    username: String,
    onInbox: () -> Unit,
    onSetup: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val recorder = remember(communication) { TouchRecorder(onSample = communication::streamSample) }
    val player = remember { AndroidTouchPlayer(context.applicationContext) }
    val outbox by communication.outbox.collectAsState(initial = emptyList())
    val liveStatus by communication.liveStatus.collectAsState()
    var recording by remember { mutableStateOf(false) }
    var elapsedMillis by remember { mutableIntStateOf(0) }
    var touch by remember { mutableStateOf<Touch?>(null) }
    var sentId by remember { mutableStateOf<String?>(null) }

    DisposableEffect(liveStatus) {
        val window = (context as? android.app.Activity)?.window
        if (liveStatus.name != "OFF") window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    fun finishRecording(capturePartialInterval: Boolean) {
        if (!recorder.isRecording && !recording) return
        if (capturePartialInterval && recorder.isRecording) recorder.sample()
        touch = recorder.stop()
        communication.endLiveTouch()
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
        Spacer(Modifier.height(16.dp))
        RecordingPad(recorder, recording, if (recording) null else touch, Modifier.weight(1f))
        sendStatus?.let { Text("Send: ${it.name.lowercase()}", color = Ink.copy(alpha = .65f), modifier = Modifier.padding(top = 8.dp)) }
        Spacer(Modifier.height(12.dp))
        if (recording) {
            Button(onClick = { finishRecording(true) }, colors = ButtonDefaults.buttonColors(containerColor = Ink), modifier = Modifier.fillMaxWidth()) { Text("Stop recording") }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = {
                    player.cancel()
                    communication.setInteractionBusy(true)
                    communication.beginLiveTouch(recorder.samplePeriodMillis)
                    recorder.start()
                    elapsedMillis = 0
                    recording = true
                }, colors = ButtonDefaults.buttonColors(containerColor = Signal), modifier = Modifier.weight(1f)) { Text(if (touch == null) "Record" else "Again") }
                Button(onClick = {
                    touch?.let { recorded ->
                        scope.launch {
                            communication.setInteractionBusy(true)
                            try {
                                if (player.play(recorded)) delay(recorded.durationMillis + 100)
                            } finally { communication.setInteractionBusy(false) }
                        }
                    }
                }, enabled = touch?.isSilent == false, colors = ButtonDefaults.buttonColors(containerColor = Ink), modifier = Modifier.width(92.dp)) { Text("Play") }
                Button(onClick = { touch?.let { scope.launch { sentId = communication.send(it) } } }, enabled = touch?.isSilent == false && sendStatus != OutboxStatus.SENDING, modifier = Modifier.width(92.dp)) { Text("Send") }
            }
            TextButton(onClick = { if (liveStatus.name == "OFF") communication.startLive() else communication.stopLive() }) { Text(if (liveStatus.name == "OFF") "Go live" else "Live: ${liveStatus.name.lowercase()}") }
        }
    }
}

@Composable
private fun InboxScreen(communication: TouchCommunication, onBack: () -> Unit) {
    val inbox by communication.inbox.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Inbox", color = Ink, fontSize = 28.sp)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onBack) { Text("Back") }
        }
        if (inbox.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No received touches yet", color = Ink.copy(alpha = .6f)) }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(inbox, key = InboxTouch::id) { item ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().background(Mint.copy(alpha = .35f), RoundedCornerShape(16.dp)).padding(14.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(item.senderUsername, color = Ink)
                            Text("${formatDuration(item.touch.durationMillis.toInt())} · ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(item.receivedAt))}", color = Ink.copy(alpha = .6f), fontSize = 12.sp)
                            Text(if (item.playedAt == null) "Not played" else "Played", color = Ink.copy(alpha = .55f), fontSize = 12.sp)
                        }
                        Button(onClick = { scope.launch { communication.play(item) } }) { Text("Play") }
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
