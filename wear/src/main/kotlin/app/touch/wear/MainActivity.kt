package app.touch.wear

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import app.touch.communication.InboxTouch
import app.touch.communication.TouchCommunication
import app.touch.core.AndroidTouchPlayer
import app.touch.core.Touch
import app.touch.core.TouchRecorder
import app.touch.core.amplitudeAt
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
        val debugSetup = if (BuildConfig.DEBUG) intent.debugSetup() else null
        setContent { TouchWearApp(communication, debugSetup) }
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

private val Charcoal = Color(0xFF101713)
private val Coral = Color(0xFFFF6B45)
private val Mint = Color(0xFFBAE8D0)
private enum class Screen { RECORDER, INBOX, SETUP }

@Composable
private fun TouchWearApp(communication: TouchCommunication, debugSetup: DebugSetup?) {
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
        Box(Modifier.fillMaxSize().systemGestureExclusion().background(Charcoal), contentAlignment = Alignment.Center) {
            when (screen) {
                Screen.SETUP -> SetupScreen(settings, communication) { screen = Screen.RECORDER }
                Screen.INBOX -> InboxScreen(communication) { screen = Screen.RECORDER }
                Screen.RECORDER -> RecorderScreen(communication, { screen = Screen.INBOX }, { screen = Screen.SETUP })
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
private fun RecorderScreen(communication: TouchCommunication, onInbox: () -> Unit, onSetup: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val recorder = remember(communication) { TouchRecorder(onSample = communication::streamSample) }
    val player = remember { AndroidTouchPlayer(context.applicationContext) }
    val outbox by communication.outbox.collectAsState(initial = emptyList())
    val liveStatus by communication.liveStatus.collectAsState()
    var recording by remember { mutableStateOf(false) }
    var elapsed by remember { mutableIntStateOf(0) }
    var touch by remember { mutableStateOf<Touch?>(null) }
    var sentId by remember { mutableStateOf<String?>(null) }

    DisposableEffect(liveStatus) {
        val window = (context as? android.app.Activity)?.window
        if (liveStatus.name != "OFF") window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    fun finish(includePartial: Boolean) {
        if (includePartial && recorder.isRecording) recorder.sample()
        touch = recorder.stop()
        communication.endLiveTouch()
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
            Button(onClick = { finish(true) }, colors = ButtonDefaults.buttonColors(backgroundColor = Charcoal), modifier = Modifier.size(52.dp)) { Text("STOP", fontSize = 10.sp) }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = {
                    player.cancel()
                    communication.setInteractionBusy(true)
                    communication.beginLiveTouch(recorder.samplePeriodMillis)
                    recorder.start()
                    elapsed = 0
                    recording = true
                }, colors = ButtonDefaults.buttonColors(backgroundColor = Coral), modifier = Modifier.size(45.dp)) { Text("REC", fontSize = 9.sp) }
                Button(onClick = {
                    touch?.let { recorded -> scope.launch {
                        communication.setInteractionBusy(true)
                        try {
                            if (player.play(recorded)) delay(recorded.durationMillis + 100)
                        } finally { communication.setInteractionBusy(false) }
                    } }
                }, enabled = touch?.isSilent == false, colors = ButtonDefaults.buttonColors(backgroundColor = Charcoal), modifier = Modifier.size(45.dp)) { Text("PLAY", fontSize = 8.sp) }
                Button(onClick = { touch?.let { scope.launch { sentId = communication.send(it) } } }, enabled = touch?.isSilent == false, colors = ButtonDefaults.buttonColors(backgroundColor = Charcoal), modifier = Modifier.size(45.dp)) { Text("SEND", fontSize = 8.sp) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(top = 3.dp)) {
                Text("Inbox", fontSize = 10.sp, modifier = Modifier.clickable(onClick = onInbox).padding(4.dp))
                Text("Setup", fontSize = 10.sp, modifier = Modifier.clickable(onClick = onSetup).padding(4.dp))
                Text(if (liveStatus.name == "OFF") "Live" else "${liveStatus.name.lowercase()}", fontSize = 10.sp, modifier = Modifier.clickable { if (liveStatus.name == "OFF") communication.startLive() else communication.stopLive() }.padding(4.dp))
            }
        }
    }
}

@Composable
private fun InboxScreen(communication: TouchCommunication, onBack: () -> Unit) {
    val inbox by communication.inbox.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    ScalingLazyColumn(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        item { Text("INBOX", color = Coral) }
        if (inbox.isEmpty()) item { Text("No touches", fontSize = 11.sp) }
        items(inbox, key = InboxTouch::id) { item ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth(.9f).background(Color(0xFF26342C), RoundedCornerShape(14.dp)).padding(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(item.senderUsername, fontSize = 12.sp)
                    Text("${formatDuration(item.touch.durationMillis.toInt())} · ${if (item.playedAt == null) "new" else "played"}", fontSize = 9.sp, color = Color.White.copy(alpha = .65f))
                }
                Button(onClick = { scope.launch { communication.play(item) } }, modifier = Modifier.size(38.dp)) { Text("▶", fontSize = 12.sp) }
            }
        }
        item { Text("Back", color = Mint, modifier = Modifier.clickable(onClick = onBack).padding(10.dp)) }
    }
}

private fun formatDuration(milliseconds: Int): String = "%d.%02d s".format(milliseconds / 1_000, (milliseconds % 1_000) / 10)
