package uk.co.tomos.sip

import android.Manifest
import android.content.pm.PackageManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import org.linphone.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

private val Void = Color(0xFF090A12)
private val Panel = Color(0xFF151725)
private val Pink = Color(0xFFFF2D9B)
private val Cyan = Color(0xFF20E7F5)
private val Muted = Color(0xFF9BA3B8)

private data class GitHubUpdate(val buildCode: Int, val releaseName: String, val releaseUrl: String)

private suspend fun fetchGitHubUpdate(): GitHubUpdate? = withContext(Dispatchers.IO) {
    val connection = (URL("https://api.github.com/repos/Spruhantom/Tomos-androidsip/releases?per_page=10").openConnection() as HttpURLConnection)
    try {
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("User-Agent", "TomOS-SIP-Android")
        if (connection.responseCode !in 200..299) {
            throw IllegalStateException("GitHub returned HTTP ${connection.responseCode}")
        }
        val releases = JSONArray(connection.inputStream.bufferedReader().use { it.readText() })
        val currentCode = BuildConfig.VERSION_CODE
        var newest: GitHubUpdate? = null
        for (index in 0 until releases.length()) {
            val release = releases.getJSONObject(index)
            if (release.optBoolean("draft", true)) continue
            val assets = release.optJSONArray("assets") ?: continue
            val hasApk = (0 until assets.length()).any { assetIndex ->
                assets.getJSONObject(assetIndex).optString("name").endsWith(".apk", ignoreCase = true)
            }
            if (!hasApk) continue
            val tag = release.optString("tag_name")
            val buildCode = Regex("-build-(\\d+)$").find(tag)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: continue
            if (buildCode > currentCode && (newest == null || buildCode > newest!!.buildCode)) {
                newest = GitHubUpdate(
                    buildCode,
                    release.optString("name").ifBlank { tag },
                    release.optString("html_url")
                )
            }
        }
        newest
    } finally {
        connection.disconnect()
    }
}

class MainActivity : ComponentActivity() {
    private var sip: TomSipEngine? = null
    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val notificationPermissionRequest = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionRequest.launch(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermissionRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
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

    fun register(
        username: String,
        authUsername: String,
        password: String,
        domain: String,
        transport: String,
        sipPort: String,
        displayName: String,
        expirySeconds: String
    ) {
        try {
            require(username.isNotBlank() && authUsername.isNotBlank() && password.isNotBlank() && domain.isNotBlank()) {
                "Enter the SIP extension, authentication username, password and server."
            }
            val port = sipPort.toIntOrNull() ?: throw IllegalArgumentException("SIP port must be a number.")
            require(port in 1..65535) { "SIP port must be between 1 and 65535." }
            val expiry = expirySeconds.toIntOrNull() ?: throw IllegalArgumentException("Registration expiry must be a number.")
            require(expiry in 60..86400) { "Registration expiry must be between 60 and 86400 seconds." }
            val cleanDomain = domain.trim().removePrefix("sip:").removePrefix("sips:")
            val auth = Factory.instance().createAuthInfo(authUsername.trim(), username.trim(), password, null, null, cleanDomain)
            core.addAuthInfo(auth)
            val params = core.createAccountParams()
            val identity = Factory.instance().createAddress("sip:$username@$cleanDomain")
            identity?.displayName = displayName.trim().ifBlank { username.trim() }
            val server = Factory.instance().createAddress("sip:$cleanDomain")
            server?.port = port
            server?.transport = when (transport) {
                "TCP" -> TransportType.Tcp
                "TLS" -> TransportType.Tls
                else -> TransportType.Udp
            }
            params.identityAddress = identity
            params.serverAddress = server
            params.expires = expiry
            params.isRegisterEnabled = true
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
        if (Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
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
    fun toggleMute() { muted = !muted; core.setMicEnabled(!muted) }
    fun toggleHold() { val call = core.currentCall ?: return; if (held) call.resume() else call.pause(); held = !held }
    fun toggleSpeaker() { speaker = !speaker; val target = if (speaker) AudioDevice.Type.Speaker else AudioDevice.Type.Earpiece; core.setOutputAudioDevice(core.audioDevices.firstOrNull { it.type == target }) }
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
    val context = LocalContext.current
    var updateMessage by remember { mutableStateOf("Checking GitHub releases…") }
    var availableUpdate by remember { mutableStateOf<GitHubUpdate?>(null) }
    var updateCheckKey by remember { mutableIntStateOf(0) }
    LaunchedEffect(updateCheckKey) {
        updateMessage = "Checking GitHub releases…"
        try {
            availableUpdate = fetchGitHubUpdate()
            updateMessage = if (availableUpdate == null) "You're running the latest published version." else "A newer version is available: ${availableUpdate!!.releaseName}"
        } catch (error: Exception) {
            availableUpdate = null
            updateMessage = "Couldn't check GitHub: ${error.message ?: "network error"}"
        }
    }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var domain by remember { mutableStateOf("") }
    var transport by remember { mutableStateOf("UDP") }
    var destination by remember { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(false) }
    var authUsername by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var sipPort by remember { mutableStateOf("5060") }
    var expirySeconds by remember { mutableStateOf("3600") }
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
                        Text("APP UPDATES", color = Cyan, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                        Spacer(Modifier.weight(1f))
                        Text("v${BuildConfig.VERSION_NAME}", color = Muted, fontSize = 11.sp)
                    }
                    Text(updateMessage, color = Color.White, fontSize = 13.sp)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { updateCheckKey++ }, modifier = Modifier.weight(1f)) {
                            Text("CHECK AGAIN")
                        }
                        if (availableUpdate != null) {
                            Button(
                                onClick = {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(availableUpdate!!.releaseUrl)))
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = Pink)
                            ) { Text("DOWNLOAD UPDATE") }
                        }
                    }
                }
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
                    val keypadRows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("*", "0", "#"))
                    keypadRows.forEach { keys ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            keys.forEach { key ->
                                OutlinedButton(
                                    onClick = { destination += key },
                                    modifier = Modifier.weight(1f).height(54.dp),
                                    shape = RoundedCornerShape(14.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                                ) {
                                    Text(key, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { destination = "" }) { Text("CLEAR", color = Pink) }
                        TextButton(onClick = { if (destination.isNotEmpty()) destination = destination.dropLast(1) }) { Text("⌫ BACKSPACE", color = Cyan) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = engine.muted, onClick = { engine.toggleMute() }, label = { Text(if (engine.muted) "MUTED" else "MUTE") })
                        FilterChip(selected = engine.speaker, onClick = { engine.toggleSpeaker() }, label = { Text("SPEAKER") })
                        FilterChip(selected = engine.held, onClick = { engine.toggleHold() }, label = { Text(if (engine.held) "RESUME" else "HOLD") })
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = { engine.call(destination) }, Modifier.weight(1f).height(52.dp), colors = ButtonDefaults.buttonColors(containerColor = Pink), shape = RoundedCornerShape(14.dp)) { Text("CALL", fontWeight = FontWeight.Bold, letterSpacing = 1.sp) }
                        OutlinedButton(onClick = { engine.hangup() }, Modifier.weight(1f).height(52.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = Cyan), shape = RoundedCornerShape(14.dp)) { Text("END CALL", fontWeight = FontWeight.Bold) }
                    }
                }
            }
            if (engine.incomingCaller != null) {
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF252039)), shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("INCOMING CALL", color = Cyan, fontWeight = FontWeight.Bold)
                        Text(engine.incomingCaller ?: "", color = Color.White, fontSize = 20.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(onClick = { engine.accept() }, Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Void)) { Text("ANSWER") }
                            Button(onClick = { engine.decline() }, Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Pink)) { Text("DECLINE") }
                        }
                    }
                }
            }
            Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("CONTACTS", color = Cyan, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                    OutlinedTextField(contactName, { contactName = it }, Modifier.fillMaxWidth(), label = { Text("Contact name") }, singleLine = true, colors = fieldColors())
                    OutlinedTextField(contactNumber, { contactNumber = it }, Modifier.fillMaxWidth(), label = { Text("Extension / SIP number") }, singleLine = true, colors = fieldColors())
                    Button(onClick = { engine.addContact(contactName, contactNumber); contactName = ""; contactNumber = "" }, Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Pink)) { Text("SAVE CONTACT") }
                    engine.contacts.forEach { entry ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(entry, Modifier.weight(1f), color = Color.White)
                            TextButton(onClick = { engine.call(entry.substringAfter("•").trim()) }) { Text("CALL", color = Cyan) }
                        }
                    }
                }
            }
            Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("RECENT CALLS", color = Cyan, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                    if (engine.history.isEmpty()) Text("No calls yet", color = Muted)
                    engine.history.take(10).forEach { Text(it, color = Color.White, fontSize = 13.sp) }
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
                        OutlinedTextField(username, { username = it }, Modifier.fillMaxWidth(), label = { Text("SIP extension / identity") }, singleLine = true, colors = fieldColors())
                        OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), label = { Text("SIP password") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), colors = fieldColors())
                        OutlinedTextField(domain, { domain = it }, Modifier.fillMaxWidth(), label = { Text("SIP server (e.g. pbx.example.com)") }, singleLine = true, colors = fieldColors())
                        Text("ADVANCED ACCOUNT SETTINGS", color = Pink, fontSize = 10.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold)
                        OutlinedTextField(authUsername, { authUsername = it }, Modifier.fillMaxWidth(), label = { Text("Authentication username (blank = extension)") }, singleLine = true, colors = fieldColors())
                        OutlinedTextField(displayName, { displayName = it }, Modifier.fillMaxWidth(), label = { Text("Caller ID / display name") }, singleLine = true, colors = fieldColors())
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedTextField(sipPort, { sipPort = it.filter(Char::isDigit).take(5) }, Modifier.weight(1f), label = { Text("SIP port") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), colors = fieldColors())
                            OutlinedTextField(expirySeconds, { expirySeconds = it.filter(Char::isDigit).take(5) }, Modifier.weight(1f), label = { Text("Expiry (seconds)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), colors = fieldColors())
                        }
                        Text("TRANSPORT", color = Muted, fontSize = 10.sp, letterSpacing = 1.5.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("UDP", "TCP", "TLS").forEach { item ->
                                FilterChip(selected = transport == item, onClick = { transport = item }, label = { Text(item) }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Pink, selectedLabelColor = Color.White))
                            }
                        }
                        Button(
                            onClick = {
                                engine.register(username, authUsername.ifBlank { username }, password, domain, transport, sipPort, displayName, expirySeconds)
                            },
                            Modifier.fillMaxWidth().height(50.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Void),
                            shape = RoundedCornerShape(14.dp)
                        ) {
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
