package uk.co.tomos.sip

import android.Manifest
import android.content.pm.PackageManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import java.io.File
import androidx.core.content.FileProvider
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

private val Void = Color(0xFF090A12)
private val Panel = Color(0xFF151725)
private val Pink = Color(0xFFFF2D9B)
private val Cyan = Color(0xFF20E7F5)
private val Muted = Color(0xFF9BA3B8)

private data class GitHubUpdate(val buildCode: Int, val releaseName: String, val apkUrl: String)

data class AudioCodecOption(val id: String, val label: String, val details: String, val enabled: Boolean)
data class SipContact(val id: String, val name: String, val number: String)

private fun normalizeSipIdentity(value: String): String {
    val withoutScheme = value.trim().removePrefix("sip:").removePrefix("sips:")
    val userPart = withoutScheme.substringBefore("@").substringBefore(";")
    return userPart.filter { it.isLetterOrDigit() || it == '+' }.lowercase()
}

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
            val apkAsset = (0 until assets.length())
                .map { assets.getJSONObject(it) }
                .firstOrNull { it.optString("name").endsWith(".apk", ignoreCase = true) }
                ?: continue
            val apkUrl = apkAsset.optString("browser_download_url")
            if (!apkUrl.startsWith("https://github.com/")) continue
            val tag = release.optString("tag_name")
            val buildCode = Regex("-build-(\\d+)$").find(tag)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: continue
            if (buildCode > currentCode && (newest == null || buildCode > newest!!.buildCode)) {
                newest = GitHubUpdate(
                    buildCode,
                    release.optString("name").ifBlank { tag },
                    apkUrl
                )
            }
        }
        newest
    } finally {
        connection.disconnect()
    }
}


private suspend fun downloadUpdateApk(
    context: Context,
    update: GitHubUpdate,
    onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit
): File = withContext(Dispatchers.IO) {
    val targetDir = File(context.cacheDir, "updates").apply { mkdirs() }
    val target = File(targetDir, "tomos-sip-build-${update.buildCode}.apk")
    val connection = (URL(update.apkUrl).openConnection() as HttpURLConnection)
    try {
        connection.connectTimeout = 15_000
        connection.readTimeout = 60_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "TomOS-SIP-Android")
        connection.setRequestProperty("Accept", "application/octet-stream")
        val response = connection.responseCode
        if (response !in 200..299) throw IllegalStateException("APK download failed (HTTP $response)")
        val expectedLength = connection.contentLengthLong
        var downloadedBytes = 0L
        connection.inputStream.use { input ->
            target.outputStream().buffered().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    downloadedBytes += count
                    withContext(Dispatchers.Main) {
                        onProgress(downloadedBytes, expectedLength)
                    }
                }
                output.flush()
            }
        }
        if (!target.isFile || target.length() < 1_000_000L) {
            target.delete()
            throw IllegalStateException("The downloaded APK is incomplete.")
        }
        if (expectedLength > 0 && target.length() != expectedLength) {
            target.delete()
            throw IllegalStateException("APK download was incomplete. Please try again.")
        }
        target
    } finally {
        connection.disconnect()
    }
}

private const val ACTION_ANSWER_CALL = "uk.co.tomos.sip.action.ANSWER_CALL"
private const val ACTION_DECLINE_CALL = "uk.co.tomos.sip.action.DECLINE_CALL"

class MainActivity : ComponentActivity() {
    private var sip: TomSipEngine? = null
    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val notificationPermissionRequest = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionRequest.launch(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermissionRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        sip = (application as TomosSipApplication).sipEngine
        // Start Linphone's SDK service while the activity is visible. The service
        // keeps the SDK core alive when the UI is backgrounded and across task removal.
        val serviceIntent = Intent(this, org.linphone.core.tools.service.CoreService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(this, serviceIntent)
        } else {
            startService(serviceIntent)
        }
        handleCallAction(intent)
        setContent { TomSipScreen(sip!!) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleCallAction(intent)
    }

    private fun handleCallAction(intent: Intent?) {
        when (intent?.action) {
            ACTION_ANSWER_CALL -> sip?.accept()
            ACTION_DECLINE_CALL -> sip?.decline()
        }
    }

    // The Application owns the SIP engine. Destroying/recreating this Activity must
    // never stop the Core or drop active registrations/calls.
    override fun onDestroy() { sip = null; super.onDestroy() }
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
    var activeCall by mutableStateOf(false)
        private set
    var activeCallName by mutableStateOf("Unknown caller")
        private set
    var callStartedAtMillis by mutableLongStateOf(0L)
        private set
    var muted by mutableStateOf(false)
        private set
    var held by mutableStateOf(false)
        private set
    var speaker by mutableStateOf(false)
        private set
    var audioCodecs by mutableStateOf<List<AudioCodecOption>>(emptyList())
        private set
    var codecStatus by mutableStateOf("Codec preferences apply to new calls.")
        private set
    private val prefs = context.getSharedPreferences("tomos_sip", 0)
    private val profileStore = SipAccountProfileStore(context)
    private val credentialStore = SipCredentialStore(context)
    private val runtimeAccounts = mutableMapOf<String, Account>()
    private val accountIdsByIdentity = mutableMapOf<String, String>()
    var sipAccounts by mutableStateOf(profileStore.load())
        private set
    var registrationStates by mutableStateOf<Map<String, SipAccountRegistration>>(emptyMap())
        private set
    var selectedSipAccountId by mutableStateOf(profileStore.selectedAccountId())
        private set
    var history by mutableStateOf(prefs.getString("history", "")!!.lines().filter { it.isNotBlank() })
        private set
    var contacts by mutableStateOf(loadContacts())
        private set

    private fun loadContacts(): List<SipContact> {
        val structured = prefs.getString("contacts_v2", null)
        if (structured != null) return try {
            val json = JSONArray(structured)
            (0 until json.length()).mapNotNull { index ->
                val item = json.optJSONObject(index) ?: return@mapNotNull null
                val name = item.optString("name").trim()
                val number = item.optString("number").trim()
                if (name.isBlank() || number.isBlank()) null else SipContact(
                    item.optString("id").ifBlank { java.util.UUID.randomUUID().toString() }, name, number
                )
            }
        } catch (_: Exception) { emptyList() }
        return prefs.getString("contacts", "").orEmpty().lines().mapNotNull { line ->
            if (line.isBlank()) return@mapNotNull null
            val name = line.substringBefore(" • ").trim()
            val number = line.substringAfter(" • ", "").trim()
            if (name.isBlank() || number.isBlank()) null else SipContact(java.util.UUID.randomUUID().toString(), name, number)
        }
    }

    private fun persistContacts(updated: List<SipContact>) {
        val json = JSONArray()
        updated.forEach { contact -> json.put(org.json.JSONObject().put("id", contact.id).put("name", contact.name).put("number", contact.number)) }
        prefs.edit().putString("contacts_v2", json.toString()).apply()
        contacts = updated
    }

    fun resolveCallerName(username: String?, displayName: String?): String {
        val identity = username?.let(::normalizeSipIdentity).orEmpty()
        val match = contacts.firstOrNull { identity.isNotBlank() && normalizeSipIdentity(it.number) == identity }
        return match?.name ?: displayName?.takeIf { it.isNotBlank() } ?: username?.takeIf { it.isNotBlank() } ?: "Unknown caller"
    }

    fun saveContact(id: String?, name: String, number: String): String? {
        val cleanName = name.trim()
        val cleanNumber = number.trim()
        if (cleanName.isBlank() || cleanNumber.isBlank()) return "Enter a contact name and SIP number."
        val normalized = normalizeSipIdentity(cleanNumber)
        if (normalized.isBlank()) return "Enter a valid extension, phone number or SIP address."
        if (contacts.any { it.id != id && normalizeSipIdentity(it.number) == normalized }) return "A contact with this number already exists."
        val updated = contacts.toMutableList()
        val index = updated.indexOfFirst { it.id == id }
        val contact = SipContact(id ?: java.util.UUID.randomUUID().toString(), cleanName, cleanNumber)
        if (index >= 0) updated[index] = contact else updated.add(contact)
        persistContacts(updated.sortedBy { it.name.lowercase() })
        return null
    }

    fun deleteContact(id: String) = persistContacts(contacts.filterNot { it.id == id })
    private val appContext = context
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var networkCallbackRegistered = false
    private val recoveryHandler = Handler(Looper.getMainLooper())
    private var pendingNetworkRefresh: Runnable? = null
    private var pendingRegistrationRetry: Runnable? = null
    private var registrationRetryAttempt = 0
    private val maxRegistrationRetries = 3
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            // Debounce network handovers: Android can report several callbacks while
            // Wi-Fi/mobile routing changes. Refresh once the new default route settles.
            pendingNetworkRefresh?.let(recoveryHandler::removeCallbacks)
            val refresh = Runnable {
                pendingNetworkRefresh = null
                registrationRetryAttempt = 0
                pendingRegistrationRetry?.let(recoveryHandler::removeCallbacks)
                pendingRegistrationRetry = null
                runCatching { core.refreshRegisters() }
            }
            pendingNetworkRefresh = refresh
            recoveryHandler.postDelayed(refresh, 1_500L)
        }
        override fun onLost(network: Network) {
            // Do not refresh on loss; wait for onAvailable for the replacement route.
            pendingNetworkRefresh?.let(recoveryHandler::removeCallbacks)
            pendingNetworkRefresh = null
        }
    }

    init {
        if (prefs.getString("contacts_v2", null) == null && contacts.isNotEmpty()) persistContacts(contacts)
        core.addListener(object : CoreListenerStub() {
            override fun onAccountRegistrationStateChanged(core: Core, account: Account, state: RegistrationState, message: String) {
                val identityKey = account.params.identityAddress?.asStringUriOnly().orEmpty()
                val profileId = accountIdsByIdentity[identityKey]
                val stateLabel = when (state) {
                    RegistrationState.Ok -> "Registered"
                    RegistrationState.Progress -> "Registering…"
                    RegistrationState.Failed -> "Registration failed"
                    RegistrationState.Cleared -> "Not registered"
                    else -> message
                }
                if (profileId != null) {
                    registrationStates = registrationStates + (profileId to SipAccountRegistration(profileId, stateLabel, message))
                    registered = registrationStates.values.any { it.state == "Registered" }
                    if (state == RegistrationState.Ok) {
                        registrationRetryAttempt = 0
                        pendingRegistrationRetry?.let(recoveryHandler::removeCallbacks)
                        pendingRegistrationRetry = null
                    } else if (state == RegistrationState.Failed) {
                        scheduleRegistrationRetry()
                    }
                } else {
                    registered = state == RegistrationState.Ok
                }
                status = when (state) {
                    RegistrationState.Ok -> "Registered"
                    RegistrationState.Progress -> "Registering…"
                    RegistrationState.Failed -> "Registration failed: $message"
                    RegistrationState.Cleared -> "Not registered"
                    else -> message
                }
                updateStatusNotification()
            }
            override fun onCallStateChanged(core: Core, call: Call, state: Call.State, message: String) {
                val remoteName = resolveCallerName(call.remoteAddress?.username, call.remoteAddress?.displayName)
                if (state == Call.State.IncomingReceived || state == Call.State.IncomingEarlyMedia) {
                    incomingCaller = remoteName
                    activeCallName = remoteName
                    activeCall = true
                    postIncomingNotification(remoteName)
                }
                if (state == Call.State.OutgoingInit || state == Call.State.OutgoingProgress || state == Call.State.OutgoingRinging) {
                    activeCallName = remoteName
                    activeCall = true
                }
                if (state == Call.State.Connected || state == Call.State.StreamsRunning) {
                    incomingCaller = null
                    activeCall = true
                    if (callStartedAtMillis == 0L) callStartedAtMillis = SystemClock.elapsedRealtime()
                }
                if (state == Call.State.Paused) activeCall = true
                if (state == Call.State.End || state == Call.State.Released || state == Call.State.Error) {
                    incomingCaller = null
                    notificationManager().cancel(1001)
                    activeCall = false
                    callStartedAtMillis = 0L
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
                updateStatusNotification()
            }
        })
        core.start()
        // Re-attempt SIP registration when Android reports a new default network.
        // Keep the callback for the lifetime of the application-owned SIP engine.
        runCatching {
            connectivityManager.registerDefaultNetworkCallback(networkCallback)
            networkCallbackRegistered = true
        }
        refreshAudioCodecs(applySavedPreferences = true)
        // Restore saved account profiles only when their encrypted credentials are available.
        sipAccounts.forEach { profile ->
            runCatching { credentialStore.read(profile.id) }.getOrNull()?.let { secret ->
                runCatching { configureAccount(profile, secret, makeDefault = false) }
                    .onFailure { registrationStates = registrationStates + (profile.id to SipAccountRegistration(profile.id, "Configuration failed", it.message.orEmpty())) }
            } ?: run {
                registrationStates = registrationStates + (profile.id to SipAccountRegistration(profile.id, "Password required", "Enter the password to register this account again."))
            }
        }
        selectedSipAccountId?.let(::selectOutgoingAccount)
    }

    private fun scheduleRegistrationRetry() {
        if (pendingRegistrationRetry != null || registrationRetryAttempt >= maxRegistrationRetries) return
        val delayMillis = 5_000L * (1L shl registrationRetryAttempt)
        registrationRetryAttempt += 1
        val retry = Runnable {
            pendingRegistrationRetry = null
            // Avoid retrying while Android has no active default network.
            if (connectivityManager.activeNetwork != null) {
                runCatching { core.refreshRegisters() }
            }
        }
        pendingRegistrationRetry = retry
        recoveryHandler.postDelayed(retry, delayMillis)
    }

    private fun codecId(payloadType: PayloadType): String =
        "${payloadType.mimeType.lowercase()}/${payloadType.clockRate}/${payloadType.channels}"

    private fun refreshAudioCodecs(applySavedPreferences: Boolean = false) {
        val payloadTypes = core.audioPayloadTypes
        if (applySavedPreferences) {
            val saved = prefs.getStringSet("enabled_audio_codecs", null)
            if (saved != null) {
                payloadTypes.forEach { payload ->
                    payload.enable(codecId(payload) in saved)
                }
                if (payloadTypes.none { it.enabled() }) {
                    payloadTypes.firstOrNull()?.enable(true)
                }
            }
        }
        audioCodecs = payloadTypes.map { payload ->
            val rate = if (payload.clockRate >= 1000) "${payload.clockRate / 1000} kHz" else "${payload.clockRate} Hz"
            val channels = if (payload.channels > 1) " • ${payload.channels} channels" else ""
            AudioCodecOption(
                codecId(payload),
                payload.mimeType.uppercase(),
                rate + channels,
                payload.enabled()
            )
        }
    }

    fun setAudioCodecEnabled(id: String, enabled: Boolean) {
        val payloadTypes = core.audioPayloadTypes
        val target = payloadTypes.firstOrNull { codecId(it) == id } ?: return
        if (!enabled && payloadTypes.count { it.enabled() } <= 1) {
            codecStatus = "At least one audio codec must remain enabled."
            return
        }
        val result = target.enable(enabled)
        if (result != 0) {
            codecStatus = "Linphone could not change this codec."
            return
        }
        val enabledIds = payloadTypes.filter { it.enabled() }.map { codecId(it) }.toSet()
        prefs.edit().putStringSet("enabled_audio_codecs", enabledIds).apply()
        codecStatus = "Codec preferences saved. They apply to new calls."
        refreshAudioCodecs()
    }

    fun register(
        username: String,
        authUsername: String,
        password: String,
        domain: String,
        transport: String,
        sipPort: String,
        displayName: String,
        expirySeconds: String,
        editAccountId: String? = null
    ) {
        try {
            val cleanDomain = domain.trim().removePrefix("sip:").removePrefix("sips:")
            val transportType = runCatching { SipTransport.valueOf(transport.uppercase()) }.getOrDefault(SipTransport.UDP)
            val matching = sipAccounts.filter {
                it.id != editAccountId &&
                    it.username.equals(username.trim(), ignoreCase = true) &&
                    it.domain.equals(cleanDomain, ignoreCase = true) &&
                    it.transport == transportType &&
                    it.port == (sipPort.toIntOrNull() ?: 0)
            }
            require(matching.isEmpty()) { "This SIP identity is already configured. Edit the existing account instead." }
            val existing = sipAccounts.firstOrNull { it.id == editAccountId }
                ?: sipAccounts.firstOrNull {
                    it.username.equals(username.trim(), ignoreCase = true) &&
                        it.domain.equals(cleanDomain, ignoreCase = true) &&
                        it.transport == transportType &&
                        it.port == (sipPort.toIntOrNull() ?: 0)
                }
            val profile = SipAccountProfile(
                id = existing?.id ?: java.util.UUID.randomUUID().toString(),
                label = displayName.trim().ifBlank { username.trim() },
                username = username.trim(),
                authUsername = authUsername.trim(),
                domain = cleanDomain,
                transport = transportType,
                port = sipPort.toIntOrNull() ?: 0,
                displayName = displayName.trim().ifBlank { username.trim() },
                expirySeconds = expirySeconds.toIntOrNull() ?: 0
            )
            val validationError = validateSipAccount(profile)
            require(validationError == null) { validationError ?: "Invalid SIP account." }
            runtimeAccounts.remove(profile.id)?.let { previous ->
                accountIdsByIdentity.remove(previous.params.identityAddress?.asStringUriOnly())
                core.removeAccount(previous)
            }
            credentialStore.save(profile.id, password)
            persistAccountProfile(profile)
            configureAccount(profile, password, makeDefault = true)
        } catch (e: Exception) { status = e.message ?: "Unable to configure SIP account" }
    }

    private fun persistAccountProfile(profile: SipAccountProfile) {
        val updated = sipAccounts.filterNot { it.id == profile.id } + profile
        profileStore.save(updated)
        sipAccounts = updated
    }

    private fun configureAccount(profile: SipAccountProfile, password: String, makeDefault: Boolean) {
        require(profile.enabled) { "This SIP account is disabled." }
        val identityText = "sip:${profile.username}@${profile.domain}"
        val identity = Factory.instance().createAddress(identityText)
            ?: throw IllegalArgumentException("Invalid SIP identity.")
        identity.displayName = profile.displayName
        val server = Factory.instance().createAddress("sip:${profile.domain}")
            ?: throw IllegalArgumentException("Invalid SIP server.")
        server.port = profile.port
        server.transport = when (profile.transport) {
            SipTransport.TCP -> TransportType.Tcp
            SipTransport.TLS -> TransportType.Tls
            SipTransport.UDP -> TransportType.Udp
        }
        val auth = Factory.instance().createAuthInfo(
            profile.authUsername, profile.username, password, null, null, profile.domain
        )
        core.addAuthInfo(auth)
        val params = core.createAccountParams()
        params.identityAddress = identity
        params.serverAddress = server
        params.expires = profile.expirySeconds
        params.isRegisterEnabled = true
        val account = core.createAccount(params)
        core.addAccount(account)
        val identityKey = identity.asStringUriOnly()
        runtimeAccounts[profile.id] = account
        accountIdsByIdentity[identityKey] = profile.id
        registrationStates = registrationStates + (profile.id to SipAccountRegistration(profile.id, "Registering"))
        if (makeDefault || core.defaultAccount == null) {
            selectedSipAccountId = profile.id
            profileStore.selectAccount(profile.id)
            core.defaultAccount = account
        }
        status = "Registering…"
    }

    fun selectOutgoingAccount(accountId: String) {
        val profile = sipAccounts.firstOrNull { it.id == accountId } ?: return
        val account = runtimeAccounts[accountId] ?: run {
            registrationStates = registrationStates + (accountId to SipAccountRegistration(accountId, "Password required"))
            return
        }
        core.defaultAccount = account
        selectedSipAccountId = profile.id
        profileStore.selectAccount(profile.id)
    }

    fun editSipAccount(accountId: String) {
        if (activeCall) { status = "End the active call before editing a SIP account."; return }
        val profile = sipAccounts.firstOrNull { it.id == accountId } ?: return
        runtimeAccounts.remove(accountId)?.let { account ->
            accountIdsByIdentity.remove(account.params.identityAddress?.asStringUriOnly())
            core.removeAccount(account)
        }
        registrationStates = registrationStates + (accountId to SipAccountRegistration(accountId, "Password required", "Save the edited account with its SIP password to register it again."))
        registered = registrationStates.values.any { it.state == "Registered" }
    }

    fun setSipAccountEnabled(accountId: String, enabled: Boolean) {
        if (activeCall) { status = "End the active call before changing SIP accounts."; return }
        val current = sipAccounts.firstOrNull { it.id == accountId } ?: return
        if (current.enabled == enabled) return
        if (!enabled) {
            runtimeAccounts.remove(accountId)?.let { account ->
                accountIdsByIdentity.remove(account.params.identityAddress?.asStringUriOnly())
                core.removeAccount(account)
            }
            val updated = current.copy(enabled = false)
            persistAccountProfile(updated)
            registrationStates = registrationStates + (accountId to SipAccountRegistration(accountId, "Disabled"))
            if (selectedSipAccountId == accountId) {
                val next = sipAccounts.firstOrNull { it.id != accountId && it.enabled && runtimeAccounts.containsKey(it.id) }
                if (next != null) selectOutgoingAccount(next.id) else {
                    selectedSipAccountId = null
                    profileStore.selectAccount(null)
                    core.defaultAccount = null
                }
            }
        } else {
            val updated = current.copy(enabled = true)
            persistAccountProfile(updated)
            val secret = runCatching { credentialStore.read(accountId) }.getOrNull()
            if (secret == null) {
                registrationStates = registrationStates + (accountId to SipAccountRegistration(accountId, "Password required", "Enter the SIP password to enable this account."))
            } else {
                runCatching { configureAccount(updated, secret, makeDefault = false) }
                    .onFailure { registrationStates = registrationStates + (accountId to SipAccountRegistration(accountId, "Configuration failed", it.message.orEmpty())) }
            }
        }
        registered = registrationStates.values.any { it.state == "Registered" }
    }

    fun restoreSipAccountPassword(accountId: String, password: String) {
        if (password.isBlank()) { status = "Enter the SIP password."; return }
        val profile = sipAccounts.firstOrNull { it.id == accountId } ?: return
        try {
            credentialStore.save(accountId, password)
            if (profile.enabled) configureAccount(profile, password, makeDefault = selectedSipAccountId == accountId || core.defaultAccount == null)
            status = "SIP credentials saved. Registering…"
        } catch (error: Exception) {
            status = error.message ?: "Unable to restore SIP credentials."
        }
    }

    fun removeSipAccount(accountId: String) {
        if (activeCall) {
            status = "End the active call before removing a SIP account."
            return
        }
        runtimeAccounts.remove(accountId)?.let { account ->
            accountIdsByIdentity.remove(account.params.identityAddress?.asStringUriOnly())
            core.removeAccount(account)
        }
        runCatching { credentialStore.delete(accountId) }
        val updated = sipAccounts.filterNot { it.id == accountId }
        profileStore.save(updated)
        sipAccounts = updated
        registrationStates = registrationStates - accountId
        if (selectedSipAccountId == accountId) {
            val next = updated.firstOrNull { runtimeAccounts.containsKey(it.id) }
            if (next != null) selectOutgoingAccount(next.id) else {
                selectedSipAccountId = null
                profileStore.selectAccount(null)
                core.defaultAccount = null
            }
        }
        registered = registrationStates.values.any { it.state == "Registered" }
    }

    fun call(destination: String) {
        try {
            val selectedId = selectedSipAccountId
            require(selectedId != null && registrationStates[selectedId]?.state == "Registered") {
                "Select a registered SIP account before calling."
            }
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

    private fun notificationManager(): NotificationManager =
        appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun canPostNotifications(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            androidx.core.content.ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun appPendingIntent(): PendingIntent? {
        val intent = appContext.packageManager.getLaunchIntentForPackage(appContext.packageName) ?: return null
        return PendingIntent.getActivity(appContext, 1002, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun updateStatusNotification() {
        if (!canPostNotifications()) return
        val manager = notificationManager()
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel("sip_status", "SIP connection", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shows TomOS SIP registration and active-call status"
                    setShowBadge(false)
                }
            )
        }
        if (!registered && !activeCall) {
            manager.cancel(1002)
            return
        }
        val title = if (activeCall) "TomOS SIP • $callStatus" else "TomOS SIP • Registered"
        val detail = if (activeCall) {
            if (callStartedAtMillis > 0L) activeCallName else "Call with $activeCallName"
        } else "Your SIP account is registered and ready for calls"
        val notification = NotificationCompat.Builder(appContext, "sip_status")
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setContentTitle(title)
            .setContentText(detail)
            .setCategory(if (activeCall) NotificationCompat.CATEGORY_CALL else NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(appPendingIntent())
            .build()
        manager.notify(1002, notification)
    }

    private fun callActionPendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(appContext, MainActivity::class.java).apply {
            this.action = action
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            appContext, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun postIncomingNotification(caller: String) {
        if (!canPostNotifications()) return
        val manager = notificationManager()
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel("incoming_calls", "Incoming calls", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Alerts for incoming TomOS SIP calls"
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                    enableVibration(true)
                }
            )
        }
        val answerIntent = callActionPendingIntent(ACTION_ANSWER_CALL, 1101)
        val declineIntent = callActionPendingIntent(ACTION_DECLINE_CALL, 1102)
        val fullScreenIntent = appContext.packageManager.getLaunchIntentForPackage(appContext.packageName)?.let { launch ->
            launch.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            PendingIntent.getActivity(
                appContext, 1100, launch,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
        val notification = NotificationCompat.Builder(appContext, "incoming_calls")
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle("TomOS SIP • Incoming call")
            .setContentText(caller)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setAutoCancel(false)
            .setTimeoutAfter(60_000L)
            .setContentIntent(fullScreenIntent)
            .setFullScreenIntent(fullScreenIntent, true)
            .addAction(android.R.drawable.sym_action_call, "Answer", answerIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Decline", declineIntent)
            .build()
        manager.notify(1001, notification)
    }
    fun accept() {
        notificationManager().cancel(1001)
        core.currentCall?.accept()
        incomingCaller = null
    }
    fun decline() {
        notificationManager().cancel(1001)
        core.currentCall?.decline(Reason.Declined)
        incomingCaller = null
    }
    fun toggleMute() { muted = !muted; core.setMicEnabled(!muted) }
    fun toggleHold() { val call = core.currentCall ?: return; if (held) call.resume() else call.pause(); held = !held }
    fun toggleSpeaker() { speaker = !speaker; val target = if (speaker) AudioDevice.Type.Speaker else AudioDevice.Type.Earpiece; core.setOutputAudioDevice(core.audioDevices.firstOrNull { it.type == target }) }
    fun hangup() { core.currentCall?.terminate(); callStatus = "Call ended" }
    fun shutdown() {
        try {
            pendingNetworkRefresh?.let(recoveryHandler::removeCallbacks)
            pendingNetworkRefresh = null
            pendingRegistrationRetry?.let(recoveryHandler::removeCallbacks)
            pendingRegistrationRetry = null
            if (networkCallbackRegistered) {
                connectivityManager.unregisterNetworkCallback(networkCallback)
                networkCallbackRegistered = false
            }
            notificationManager().cancel(1001)
            notificationManager().cancel(1002)
            core.stop()
        } catch (_: Exception) { }
    }
}

@Composable
private fun TomSipScreen(engine: TomSipEngine) {
    val context = LocalContext.current
    val updateScope = rememberCoroutineScope()
    var updateDownloading by remember { mutableStateOf(false) }
    var updateDownloadedBytes by remember { mutableLongStateOf(0L) }
    var updateTotalBytes by remember { mutableLongStateOf(-1L) }
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
    var editingSipAccountId by remember { mutableStateOf<String?>(null) }
    var credentialAccountId by remember { mutableStateOf<String?>(null) }
    var credentialPassword by remember { mutableStateOf("") }
    var authUsername by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var sipPort by remember { mutableStateOf("5060") }
    var expirySeconds by remember { mutableStateOf("3600") }
    var contactName by remember { mutableStateOf("") }
    var contactNumber by remember { mutableStateOf("") }
    var contactSearch by remember { mutableStateOf("") }
    var editingContactId by remember { mutableStateOf<String?>(null) }
    var contactError by remember { mutableStateOf<String?>(null) }
    var selectedTab by remember { mutableStateOf("Dialler") }
    val tabs = listOf("Dialler", "Contacts", "History", "Settings")
    val tabSymbols = mapOf("Dialler" to "☎", "Contacts" to "♙", "History" to "◷", "Settings" to "⚙")
    var elapsedCallSeconds by remember { mutableIntStateOf(0) }
    LaunchedEffect(engine.callStartedAtMillis, engine.activeCall) {
        while (engine.activeCall && engine.callStartedAtMillis > 0L) {
            elapsedCallSeconds = ((SystemClock.elapsedRealtime() - engine.callStartedAtMillis) / 1000L).toInt().coerceAtLeast(0)
            delay(1000)
        }
        if (!engine.activeCall) elapsedCallSeconds = 0
    }

    MaterialTheme(colorScheme = darkColorScheme(
        primary = Pink, secondary = Cyan, background = Void, surface = Panel,
        onPrimary = Color.White, onBackground = Color.White, onSurface = Color.White
    )) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = Void,
            bottomBar = {
                NavigationBar(containerColor = Panel, contentColor = Color.White) {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = selectedTab == tab,
                            onClick = { selectedTab = tab },
                            icon = { Text(tabSymbols[tab] ?: "•", fontSize = 19.sp) },
                            label = { Text(tab) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Cyan, selectedTextColor = Cyan,
                                indicatorColor = Color(0xFF303044),
                                unselectedIconColor = Muted, unselectedTextColor = Muted
                            )
                        )
                    }
                }
            }
        ) { innerPadding ->
        if (engine.activeCall) {
            ActiveCallScreen(engine = engine, elapsedSeconds = elapsedCallSeconds, modifier = Modifier.fillMaxSize().padding(innerPadding))
        } else Column(
            Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF111426), Void, Color(0xFF090A12))))
                .padding(innerPadding).verticalScroll(rememberScrollState()).padding(22.dp),
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
            if (selectedTab == "Dialler") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Your calls.", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                Text("Your network. Your control.", color = Pink, fontSize = 17.sp)
            }
            }
            if (selectedTab == "Settings") {
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
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                                        !context.packageManager.canRequestPackageInstalls()
                                    ) {
                                        updateMessage = "Allow TomOS SIP to install apps from this source, then tap Install Update again."
                                        context.startActivity(
                                            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                                        )
                                    } else if (!updateDownloading) {
                                        updateScope.launch {
                                            updateDownloading = true
                                            updateDownloadedBytes = 0L
                                            updateTotalBytes = -1L
                                            updateMessage = "Downloading TomOS SIP update…"
                                            try {
                                                val update = availableUpdate ?: return@launch
                                                val apk = downloadUpdateApk(context, update) { downloaded, total ->
                                                    updateDownloadedBytes = downloaded
                                                    updateTotalBytes = total
                                                }
                                                val apkUri = FileProvider.getUriForFile(
                                                    context,
                                                    "${context.packageName}.fileprovider",
                                                    apk
                                                )
                                                val installIntent = Intent(Intent.ACTION_VIEW).apply {
                                                    setDataAndType(apkUri, "application/vnd.android.package-archive")
                                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                                                }
                                                context.startActivity(installIntent)
                                                updateMessage = "Downloaded. Confirm the update in Android's installer."
                                            } catch (error: Exception) {
                                                updateMessage = "Update failed: ${error.message ?: "download or installer error"}"
                                            } finally {
                                                updateDownloading = false
                                            }
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                enabled = !updateDownloading,
                                colors = ButtonDefaults.buttonColors(containerColor = Pink)
                            ) { Text(if (updateDownloading) "DOWNLOADING…" else "INSTALL UPDATE") }
                        }
                    }
                    if (updateDownloading) {
                        val progressKnown = updateTotalBytes > 0L
                        val progress = if (progressKnown) {
                            (updateDownloadedBytes.toFloat() / updateTotalBytes.toFloat()).coerceIn(0f, 1f)
                        } else 0f
                        if (progressKnown) {
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier.fillMaxWidth(),
                                color = Cyan,
                                trackColor = Color(0xFF303448)
                            )
                        } else {
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth(),
                                color = Cyan,
                                trackColor = Color(0xFF303448)
                            )
                        }
                        val downloadedMb = updateDownloadedBytes / (1024f * 1024f)
                        val sizeText = if (progressKnown) {
                            val totalMb = updateTotalBytes / (1024f * 1024f)
                            "${"%.1f".format(downloadedMb)} MB of ${"%.1f".format(totalMb)} MB"
                        } else {
                            "${"%.1f".format(downloadedMb)} MB downloaded"
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(sizeText, color = Muted, fontSize = 11.sp)
                            if (progressKnown) Text("${(progress * 100).toInt()}%", color = Cyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
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
            if (selectedTab == "Dialler") {
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
            if (selectedTab == "Contacts") {
            Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("CONTACTS & CALLER ID", color = Cyan, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                    Text("Saved names are matched to incoming SIP usernames and extensions.", color = Muted, fontSize = 12.sp)
                    OutlinedTextField(contactSearch, { contactSearch = it }, Modifier.fillMaxWidth(), label = { Text("Search contacts") }, singleLine = true, colors = fieldColors())
                    Divider(color = Color(0xFF3A4054))
                    Text(if (editingContactId == null) "ADD CONTACT" else "EDIT CONTACT", color = Pink, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    OutlinedTextField(contactName, { contactName = it; contactError = null }, Modifier.fillMaxWidth(), label = { Text("Contact name") }, singleLine = true, colors = fieldColors())
                    OutlinedTextField(contactNumber, { contactNumber = it; contactError = null }, Modifier.fillMaxWidth(), label = { Text("Extension, phone number or SIP address") }, singleLine = true, colors = fieldColors())
                    if (contactError != null) Text(contactError!!, color = Pink, fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            val error = engine.saveContact(editingContactId, contactName, contactNumber)
                            contactError = error
                            if (error == null) { contactName = ""; contactNumber = ""; editingContactId = null }
                        }, Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Pink)) { Text(if (editingContactId == null) "SAVE CONTACT" else "UPDATE CONTACT") }
                        if (editingContactId != null) OutlinedButton(onClick = { contactName = ""; contactNumber = ""; editingContactId = null; contactError = null }) { Text("CANCEL") }
                    }
                    Divider(color = Color(0xFF3A4054))
                    val filteredContacts = engine.contacts.filter { it.name.contains(contactSearch, ignoreCase = true) || it.number.contains(contactSearch, ignoreCase = true) }
                    Text(filteredContacts.size.toString() + " CONTACTS", color = Muted, fontSize = 10.sp, letterSpacing = 1.sp)
                    if (filteredContacts.isEmpty()) Text("No matching contacts", color = Muted, fontSize = 13.sp)
                    filteredContacts.forEach { contact ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(38.dp).background(Color(0xFF252039), RoundedCornerShape(19.dp)), contentAlignment = Alignment.Center) { Text(contact.name.take(1).uppercase(), color = Cyan, fontWeight = FontWeight.Bold) }
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(contact.name, color = Color.White, fontWeight = FontWeight.SemiBold)
                                Text(contact.number, color = Muted, fontSize = 11.sp)
                            }
                            TextButton(onClick = { engine.call(contact.number) }) { Text("CALL", color = Cyan) }
                            TextButton(onClick = { editingContactId = contact.id; contactName = contact.name; contactNumber = contact.number; contactError = null }) { Text("EDIT", color = Pink) }
                            TextButton(onClick = { engine.deleteContact(contact.id); if (editingContactId == contact.id) { editingContactId = null; contactName = ""; contactNumber = "" } }) { Text("DELETE", color = Pink) }
                        }
                    }
                }
            }
            }
            if (selectedTab == "History") {
            Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("RECENT CALLS", color = Cyan, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                    if (engine.history.isEmpty()) Text("No calls yet", color = Muted)
                    engine.history.take(10).forEach { Text(it, color = Color.White, fontSize = 13.sp) }
                }
            }
            }
            if (selectedTab == "Settings") {
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
                                engine.register(username, authUsername.ifBlank { username }, password, domain, transport, sipPort, displayName, expirySeconds, editingSipAccountId)
                                if (engine.status.startsWith("Registering")) { editingSipAccountId = null; password = "" }
                            },
                            Modifier.fillMaxWidth().height(50.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Void),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Text(if (editingSipAccountId == null) "REGISTER ACCOUNT" else "SAVE ACCOUNT CHANGES", fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                        }
                    }
                }
            }
            Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("SIP ACCOUNTS", color = Cyan, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                    Text("Add accounts using the form above. Choose which registered identity is used for outgoing calls.", color = Muted, fontSize = 12.sp)
                    if (engine.sipAccounts.isEmpty()) Text("No saved SIP accounts yet.", color = Muted, fontSize = 12.sp)
                    engine.sipAccounts.forEach { account ->
                        val accountState = engine.registrationStates[account.id]
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(account.label, color = Color.White, fontWeight = FontWeight.SemiBold)
                                Text("${account.username}@${account.domain} • ${account.transport.name}", color = Muted, fontSize = 11.sp)
                                Text(accountState?.state ?: "Not registered", color = if (accountState?.state == "Registered") Cyan else Pink, fontSize = 11.sp)
                            }
                            if (engine.selectedSipAccountId == account.id) {
                                Text("DEFAULT", color = Cyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            } else {
                                TextButton(onClick = { engine.selectOutgoingAccount(account.id) }) { Text("USE", color = Cyan) }
                            }
                            TextButton(onClick = {
                                editingSipAccountId = account.id
                                username = account.username
                                authUsername = account.authUsername
                                domain = account.domain
                                transport = account.transport.name
                                sipPort = account.port.toString()
                                displayName = account.displayName
                                expirySeconds = account.expirySeconds.toString()
                                password = ""
                                showSettings = true
                            }) { Text("EDIT", color = Cyan) }
                            TextButton(onClick = { engine.setSipAccountEnabled(account.id, !account.enabled) }) {
                                Text(if (account.enabled) "DISABLE" else "ENABLE", color = if (account.enabled) Pink else Cyan)
                            }
                            if (accountState?.state == "Password required") {
                                TextButton(onClick = { credentialAccountId = account.id; credentialPassword = "" }) { Text("ENTER PASSWORD", color = Cyan) }
                            }
                            TextButton(onClick = { engine.removeSipAccount(account.id) }) { Text("REMOVE", color = Pink) }
                            if (credentialAccountId == account.id) {
                                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedTextField(credentialPassword, { credentialPassword = it }, Modifier.fillMaxWidth(), label = { Text("SIP password") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), colors = fieldColors())
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(onClick = { engine.restoreSipAccountPassword(account.id, credentialPassword); credentialAccountId = null; credentialPassword = "" }) { Text("SAVE PASSWORD") }
                                        TextButton(onClick = { credentialAccountId = null; credentialPassword = "" }) { Text("CANCEL") }
                                    }
                                }
                            }
                        }
                        Divider(color = Color(0xFF3A4054))
                    }
                }
            }
            Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("AUDIO CODECS", color = Cyan, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                    Text("Enable every codec your PBX supports. Linphone negotiates a compatible codec with the other endpoint for each call.", color = Muted, fontSize = 12.sp)
                    if (engine.audioCodecs.isEmpty()) {
                        Text("No audio codecs were reported by the Linphone SDK.", color = Pink, fontSize = 12.sp)
                    }
                    engine.audioCodecs.forEach { codec ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(codec.label, color = Color.White, fontWeight = FontWeight.SemiBold)
                                Text(codec.details, color = Muted, fontSize = 11.sp)
                            }
                            Checkbox(
                                checked = codec.enabled,
                                onCheckedChange = { engine.setAudioCodecEnabled(codec.id, it) },
                                colors = CheckboxDefaults.colors(checkedColor = Cyan, uncheckedColor = Muted)
                            )
                        }
                    }
                    Text(engine.codecStatus, color = Muted, fontSize = 11.sp)
                }
            }
            }
            Text("TOM OS  •  NEON PUNK EDITION", Modifier.fillMaxWidth(), color = Muted, fontSize = 10.sp, letterSpacing = 1.6.sp)
        }
        }
    }
}

@Composable
private fun ActiveCallScreen(engine: TomSipEngine, elapsedSeconds: Int, modifier: Modifier = Modifier) {
    val isIncoming = engine.incomingCaller != null
    Column(
        modifier.background(Brush.verticalGradient(listOf(Void, Color(0xFF111326))))
            .padding(horizontal = 24.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("TOMOS SIP", color = Cyan, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
            Spacer(Modifier.height(24.dp))
            Box(Modifier.size(112.dp).background(Panel, RoundedCornerShape(56.dp))
                .border(2.dp, Cyan.copy(alpha = 0.65f), RoundedCornerShape(56.dp)), contentAlignment = Alignment.Center) {
                Text("☎", color = Cyan, fontSize = 48.sp)
            }
            Spacer(Modifier.height(12.dp))
            Text(engine.activeCallName, color = Color.White, fontSize = 27.sp, fontWeight = FontWeight.Bold)
            Text(if (isIncoming) "INCOMING CALL" else engine.callStatus.uppercase(),
                color = if (engine.callStatus == "On hold") Pink else Cyan,
                fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
            if (engine.callStartedAtMillis > 0L) {
                Text(String.format("%02d:%02d", elapsedSeconds / 60, elapsedSeconds % 60),
                    color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Light)
            } else if (!isIncoming) Text("Connecting…", color = Muted, fontSize = 16.sp)
        }
        if (isIncoming) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                Button(onClick = { engine.decline() }, Modifier.weight(1f).height(60.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Pink), shape = RoundedCornerShape(18.dp)) {
                    Text("DECLINE", fontWeight = FontWeight.Bold)
                }
                Button(onClick = { engine.accept() }, Modifier.weight(1f).height(60.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Void), shape = RoundedCornerShape(18.dp)) {
                    Text("ANSWER", fontWeight = FontWeight.Bold)
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    CallControl("MUTE", engine.muted, "🎙") { engine.toggleMute() }
                    CallControl("SPEAKER", engine.speaker, "🔊") { engine.toggleSpeaker() }
                    CallControl(if (engine.held) "RESUME" else "HOLD", engine.held, "Ⅱ") { engine.toggleHold() }
                }
                Button(onClick = { engine.hangup() }, Modifier.fillMaxWidth(0.72f).height(64.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Pink), shape = RoundedCornerShape(22.dp)) {
                    Text("END CALL", fontSize = 16.sp, fontWeight = FontWeight.Black, letterSpacing = 1.5.sp)
                }
            }
        }
    }
}

@Composable
private fun CallControl(label: String, selected: Boolean, symbol: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalButton(onClick = onClick, modifier = Modifier.size(76.dp), shape = RoundedCornerShape(28.dp),
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = if (selected) Cyan else Panel,
                contentColor = if (selected) Void else Color.White
            )) { Text(symbol, fontSize = 25.sp) }
        Text(label, color = if (selected) Cyan else Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Cyan, unfocusedBorderColor = Color(0xFF3A4054),
    focusedLabelColor = Cyan, unfocusedLabelColor = Muted, cursorColor = Pink
)
