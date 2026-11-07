package uk.co.tomos.sip

import android.Manifest
import android.content.pm.PackageManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.media.AudioManager
import android.os.Build
import androidx.core.app.NotificationCompat
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import org.linphone.core.*

private val Void = Color(0xFF090A12)
private val Panel = Color(0xFF151725)
private val Pink = Color(0xFFFF2D9B)
private val Cyan = Color(0xFF20E7F5)
private val Muted = Color(0xFF9BA3B8)

class MainActivity : ComponentActivity() {
    private var sip: TomSipEngine? = null
    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionRequest.launch(Manifest.permission.RECORD_AUDIO)
        }
        sip = TomSipEngine(applicationContext)
        setContent { TomSipScreen(sip!!) }
    }
    override fun onDestroy() { sip?.shutdown(); sip = null; super.onDestroy() }
}

class TomSipEngine(context: android.content.Context) {
    private val core: Core = Factory.instance().createCore(null, null, context)
    var status by mutableStateOf("Not configured")
        private set
    var callStatus by mutableStateOf("Ready")
        private set
    var registered by mutableStateOf(false)
        private set
    var incomingCaller by mutableStateOf<String?>(null)
        private set
    var muted by mutableStateOf(false)
        private set
    var held by mutableStateOf(false)
        private set
    var speaker by mutableStateOf(false)
        private set
    private val prefs = context.getSharedPreferences("tomos_sip", 0)
    var history by mutableStateOf(prefs.getString("history", "")!!.lines().filter { it.isNotBlank() })
        private set
    var contacts by mutableStateOf(prefs.getString("contacts", "")!!.lines().filter { it.isNotBlank() })
        private set
    private val appContext = context
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    init {
        core.addListener(object : CoreListenerStub() {
            override fun onAccountRegistrationStateChanged(core: Core, account: Account, state: RegistrationState, message: String) {
                registered = state == RegistrationState.Ok
                status = when (state) {
                    RegistrationState.Ok -> "Registered"
                    RegistrationState.Progress -> "Registering…"
                    RegistrationState.Failed -> "Registration failed: $message"
                    RegistrationState.Cleared -> "Not registered"
                    else -> message
                }
            }
            override fun onCallStateChanged(core: Core, call: Call, state: Call.State, message: String) {
                if (state == Call.State.IncomingReceived || state == Call.State.IncomingEarlyMedia) {
                    incomingCaller = call.remoteAddress?.username ?: "Unknown caller"
                    postIncomingNotification(incomingCaller ?: "Incoming call")
                }
                if (state == Call.State.Connected || state == Call.State.StreamsRunning) incomingCaller = null
                if (state == Call.State.End || state == Call.State.Released || state == Call.State.Error) {
                    incomingCaller = null
                    history = (listOf((call.remoteAddress?.username ?: "Unknown") + " • " + state.name) + history).take(50)
                    prefs.edit().putString("history", history.joinToString("\n")).apply()
                }
                callStatus = when (state) {
                    Call.State.OutgoingInit, Call.State.OutgoingProgress -> "Calling…"
                    Call.State.OutgoingRinging -> "Ringing…"
                    Call.State.Connected, Call.State.StreamsRunning -> "Connected"
                    Call.State.Paused -> "On hold"
                    Call.State.End, Call.State.Released, Call.State.Error -> "Call ended"
                    else -> message
                }
            }
        })
        core.start()
    }

    fun register(username: String, password: String, domain: String, transport: String) {
        try {
            require(username.isNotBlank() && password.isNotBlank() && domain.isNotBlank()) { "Enter username, password and SIP server." }
            val cleanDomain = domain.trim().removePrefix("sip:").removePrefix("sips:")
            val auth = Factory.instance().createAuthInfo(username.trim(), null, password, null, null, cleanDomain)
            core.addAuthInfo(auth)
            val params = core.createAccountParams()
            val identity = Factory.instance().createAddress("sip:$username@$cleanDomain")
            val server = Factory.instance().createAddress("sip:$cleanDomain")
            server?.transport = when (transport) {
                "TCP" -> TransportType.Tcp
                "TLS" -> TransportType.Tls
                else -> TransportType.Udp
            }
            params.identityAddress = identity
            params.serverAddress = server
            params.registerEnabled = true
            val account = core.createAccount(params)
            core.addAccount(account)
            core.defaultAccount = account
            status = "Registering…"
        } catch (e: Exception) { status = e.message ?: "Unable to configure SIP account" }
    }

    fun call(destination: String) {
        try {
            require(registered) { "Register a SIP account before calling." }
            require(destination.isNotBlank()) { "Enter an extension or SIP address." }
            val target = if (destination.contains("@")) destination.trim() else {
                val domain = core.defaultAccount?.params?.serverAddress?.domain
                    ?: throw IllegalStateException("SIP server is not configured.")
                "sip:${destination.trim()}@$domain"
            }
            val address = Factory.instance().createAddress(target)
                ?: throw IllegalArgumentException("Invalid SIP destination.")
            core.inviteAddress(address)
            callStatus = "Calling…"
        } catch (e: Exception) { callStatus = e.message ?: "Call failed" }
    }

    private fun postIncomingNotification(caller: String) {
        val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel("incoming_calls", "Incoming calls", NotificationManager.IMPORTANCE_HIGH))
        val intent = appContext.packageManager.getLaunchIntentForPackage(appContext.packageName)
        val pending = intent?.let { PendingIntent.getActivity(appContext, 0, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE) }
        val notification = NotificationCompat.Builder(appContext, "incoming_calls")
            .setSmallIcon(android.R.drawable.sym_call_incoming).setContentTitle("TomOS SIP • Incoming call")
            .setContentText(caller).setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX).setAutoCancel(true).setContentIntent(pending).build()
        manager.notify(1001, notification)
    }
    fun accept() { core.currentCall?.accept(); incomingCaller = null }
    fun decline() { core.currentCall?.decline(Reason.Declined); incomingCaller = null }
    fun toggleMute() { muted = !muted; core.isMicEnabled = !muted }
    fun toggleHold() { val call = core.currentCall ?: return; if (held) call.resume() else call.pause(); held = !held }
    fun toggleSpeaker() { speaker = !speaker; audio.mode = AudioManager.MODE_IN_COMMUNICATION; @Suppress("DEPRECATION") run { audio.isSpeakerphoneOn = speaker } }
    fun addContact(name: String, number: String) {
        if (name.isBlank() || number.isBlank()) return
        contacts = (contacts + "$name • $number").distinct()
        prefs.edit().putString("contacts", contacts.joinToString("\n")).apply()
    }
    fun hangup() { core.currentCall?.terminate(); callStatus = "Call ended" }
    fun shutdown() { try { core.stop() } catch (_: Exception) { } }
}

@Composable
private fun TomSipScreen(engine: TomSipEngine) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var domain by remember { mutableStateOf("") }
    var transport by remember { mutableStateOf("UDP") }
    var destination by remember { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(true) }
    var contactName by remember { mutableStateOf("") }
    var contactNumber by remember { mutableStateOf("") }

    MaterialTheme(colorScheme = darkColorScheme(
        primary = Pink, secondary = Cyan, background = Void, surface = Panel,
        onPrimary = Color.White, onBackground = Color.White, onSurface = Color.White
    )) {
        Column(
            Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF111426), Void, Color(0xFF090A12))))
                .verticalScroll(rememberScrollState()).padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(48.dp).border(1.dp, Pink, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                    Text("T", color = Cyan, fontSize = 28.sp, fontWeight = FontWeight.Black)
                }
                Column {
                    Text("TOM OS", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
                    Text("SIP COMMUNICATOR", color = Cyan, fontSize = 11.sp, letterSpacing = 2.sp)
                }
                Spacer(Modifier.weight(1f))
                Text("v0.1.0", color = Muted, fontSize = 11.sp)
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Your calls.", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                Text("Your network. Your control.", color = Pink, fontSize = 17.sp)
            }
            Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("ACCOUNT STATUS", color = Muted, fontSize = 11.sp, letterSpacing = 1.5.sp)
                        Spacer(Modifier.weight(1f))
                        Box(Modifier.size(8.dp).background(if (engine.registered) Cyan else Pink, RoundedCornerShape(50)))
                        Spacer(Modifier.width(7.dp))
                        Text(if (engine.registered) "ONLINE" else "OFFLINE", color = if (engine.registered) Cyan else Pink, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    Text(engine.status, color = Color.White, fontSize = 15.sp)
                }
            }
            Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("DIAL PAD", color = Cyan, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                        Spacer(Modifier.weight(1f))
                        Text(engine.callStatus, color = Muted, fontSize = 12.sp)
                    }
                    OutlinedTextField(destination, { destination = it }, Modifier.fillMaxWidth(), label = { Text("Extension or SIP address") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), colors = fieldColors())
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = { engine.call(destination) }, Modifier.weight(1f).height(52.dp), colors = ButtonDefaults.buttonColors(containerColor = Pink), shape = RoundedCornerShape(14.dp)) { Text("CALL", fontWeight = FontWeight.Bold, letterSpacing = 1.sp) }
                        OutlinedButton(onClick = { engine.hangup() }, Modifier.weight(1f).height(52.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = Cyan), shape = RoundedCornerShape(14.dp)) { Text("END CALL", fontWeight = FontWeight.Bold) }
                    }
                }
            }
            Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("SIP CONFIGURATION", color = Cyan, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { showSettings = !showSettings }) { Text(if (showSettings) "HIDE" else "EDIT", color = Pink) }
                    }
                    if (showSettings) {
                        OutlinedTextField(username, { username = it }, Modifier.fillMaxWidth(), label = { Text("SIP username / extension") }, singleLine = true, colors = fieldColors())
                        OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), label = { Text("SIP password") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), colors = fieldColors())
                        OutlinedTextField(domain, { domain = it }, Modifier.fillMaxWidth(), label = { Text("SIP server (e.g. pbx.example.com)") }, singleLine = true, colors = fieldColors())
                        Text("TRANSPORT", color = Muted, fontSize = 10.sp, letterSpacing = 1.5.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("UDP", "TCP", "TLS").forEach { item ->
                                FilterChip(selected = transport == item, onClick = { transport = item }, label = { Text(item) }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Pink, selectedLabelColor = Color.White))
                            }
                        }
                        Button(onClick = { engine.register(username, password, domain, transport) }, Modifier.fillMaxWidth().height(50.dp), colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Void), shape = RoundedCornerShape(14.dp)) {
                            Text("REGISTER ACCOUNT", fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                        }
                    }
                }
            }
            Text("TOM OS  •  NEON PUNK EDITION", Modifier.fillMaxWidth(), color = Muted, fontSize = 10.sp, letterSpacing = 1.6.sp)
        }
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Cyan, unfocusedBorderColor = Color(0xFF3A4054),
    focusedLabelColor = Cyan, unfocusedLabelColor = Muted, cursorColor = Pink
)
