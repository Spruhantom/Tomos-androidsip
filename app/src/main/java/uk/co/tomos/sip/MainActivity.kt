package uk.co.tomos.sip

import android.Manifest
import android.content.pm.PackageManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
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
import androidx.core.app.Person
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateContentSize
import androidx.compose.ui.draw.scale
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
import androidx.compose.ui.platform.LocalConfiguration
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
        // Start TomOS's service for the exact application-owned SIP engine. Starting
        // Linphone CoreService here would create a second, unrelated Core.
        val serviceIntent = Intent(this, TomosSipForegroundService::class.java)
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
    var history by mutableStateOf(loadCallHistory())
        private set
    private var callStartedAtEpochMillis = 0L
    private var callConnectedAtElapsedMillis = 0L
    private var callDirection = "Outgoing"
    private var callWasConnected = false
    private var callHistoryFinalized = true
    var contacts by mutableStateOf(loadContacts())
        private set

    private fun loadCallHistory(): List<SipCallRecord> {
        val stored = prefs.getString("call_history_v2", null)
        if (stored != null) return runCatching {
            val json = JSONArray(stored)
            (0 until json.length()).mapNotNull { index ->
                val item = json.optJSONObject(index) ?: return@mapNotNull null
                SipCallRecord(
                    item.optString("id"), item.optString("peer"), item.optString("address"),
                    item.optString("direction"), item.optString("result"),
                    item.optLong("startedAt"), item.optLong("durationSeconds")
                )
            }
        }.getOrDefault(emptyList())
        // Preserve legacy entries as imported records rather than discarding history.
        return CallHistoryLogic.migrateLegacy(prefs.getString("history", "").orEmpty().lines())
    }

    private fun persistCallHistory(records: List<SipCallRecord>) {
        val json = JSONArray()
        records.forEach { record ->
            json.put(org.json.JSONObject()
                .put("id", record.id).put("peer", record.peer).put("address", record.address)
                .put("direction", record.direction).put("result", record.result)
                .put("startedAt", record.startedAt).put("durationSeconds", record.durationSeconds))
        }
        prefs.edit().putString("call_history_v2", json.toString()).apply()
        history = records
    }

    fun deleteCallRecord(id: String) = persistCallHistory(CallHistoryLogic.delete(history, id))
    fun clearCallHistory() = persistCallHistory(emptyList())

    /**
     * Validate and serialise both datasets before writing either one. A single
     * synchronous SharedPreferences commit stores both keys together, so a failed
     * restore cannot leave contacts updated while call history remains old.
     */
    fun restoreBackup(document: TomosBackupDocument) {
        val restoredContacts = requireNotNull(document.contacts)
            .sortedBy { it.name.lowercase(Locale.ROOT) }
        val restoredHistory = requireNotNull(document.callHistory).toList()
        val contactsJson = JSONArray().apply {
            restoredContacts.forEach { contact ->
                put(org.json.JSONObject()
                    .put("id", contact.id)
                    .put("name", contact.name)
                    .put("number", contact.number))
            }
        }.toString()
        val historyJson = JSONArray().apply {
            restoredHistory.forEach { record ->
                put(org.json.JSONObject()
                    .put("id", record.id)
                    .put("peer", record.peer)
                    .put("address", record.address)
                    .put("direction", record.direction)
                    .put("result", record.result)
                    .put("startedAt", record.startedAt)
                    .put("durationSeconds", record.durationSeconds))
            }
        }.toString()

        check(prefs.edit()
            .putString("contacts_v2", contactsJson)
            .putString("call_history_v2", historyJson)
            .commit()) { "Android couldn't save the restored backup. Your existing data has been kept." }

        contacts = restoredContacts
        history = restoredHistory
    }
    fun callBack(record: SipCallRecord) = call(record.address)

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
            // Wait for validated internet; Wi-Fi association alone does not mean
            // the PBX can be reached (captive portals and no-internet networks).
            scheduleNetworkRecoveryIfUsable()
        }
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                scheduleNetworkRecoveryIfUsable()
            } else {
                pendingNetworkRefresh?.let(recoveryHandler::removeCallbacks)
                pendingNetworkRefresh = null
            }
        }
        override fun onLost(network: Network) {
            // Do not refresh on loss; wait for a replacement validated route.
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
                val incomingState = state == Call.State.IncomingReceived || state == Call.State.IncomingEarlyMedia
                val outgoingState = state == Call.State.OutgoingInit || state == Call.State.OutgoingProgress || state == Call.State.OutgoingRinging
                if (incomingState || outgoingState) {
                    if (callStartedAtEpochMillis == 0L || callHistoryFinalized) {
                        callStartedAtEpochMillis = System.currentTimeMillis()
                        callConnectedAtElapsedMillis = 0L
                        callWasConnected = false
                        callHistoryFinalized = false
                        callDirection = if (incomingState) "Incoming" else "Outgoing"
                    }
                    activeCallName = remoteName
                    activeCall = true
                }
                if (incomingState) {
                    incomingCaller = remoteName
                    postIncomingNotification(remoteName)
                }
                if (state == Call.State.Connected || state == Call.State.StreamsRunning) {
                    incomingCaller = null
                    activeCall = true
                    callWasConnected = true
                    if (callConnectedAtElapsedMillis == 0L) {
                        callConnectedAtElapsedMillis = SystemClock.elapsedRealtime()
                        callStartedAtMillis = callConnectedAtElapsedMillis
                    }
                }
                when (state) {
                    Call.State.Paused -> { activeCall = true; held = true }
                    Call.State.StreamsRunning, Call.State.Connected -> held = false
                    Call.State.End, Call.State.Released, Call.State.Error -> held = false
                    else -> Unit
                }
                if (state == Call.State.End || state == Call.State.Released || state == Call.State.Error) {
                    incomingCaller = null
                    notificationManager().cancel(1001)
                    activeCall = false
                    callStartedAtMillis = 0L
                    if (!callHistoryFinalized && callStartedAtEpochMillis > 0L) {
                        val address = call.remoteAddress?.asStringUriOnly() ?: call.remoteAddress?.username ?: "Unknown"
                        val peer = resolveCallerName(call.remoteAddress?.username, call.remoteAddress?.displayName)
                        val duration = CallHistoryLogic.durationSeconds(
                            callWasConnected, callConnectedAtElapsedMillis, SystemClock.elapsedRealtime()
                        )
                        val result = CallHistoryLogic.result(
                            callDirection, callWasConnected, state == Call.State.Error
                        )
                        val record = SipCallRecord(
                            java.util.UUID.randomUUID().toString(), peer, address, callDirection,
                            result, callStartedAtEpochMillis, duration
                        )
                        persistCallHistory((listOf(record) + history).take(100))
                        callHistoryFinalized = true
                        callStartedAtEpochMillis = 0L
                    }
                }
                callStatus = when (state) {
                    Call.State.OutgoingInit, Call.State.OutgoingProgress -> "Calling…"
                    Call.State.OutgoingRinging -> "Ringing…"
                    Call.State.Connected, Call.State.StreamsRunning -> "Connected"
                    Call.State.Pausing -> "Putting call on hold…"
                    Call.State.Paused -> "On hold"
                    Call.State.Resuming -> "Resuming call…"
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

    private fun hasValidatedInternet(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun scheduleNetworkRecoveryIfUsable() {
        if (!hasValidatedInternet()) return
        pendingNetworkRefresh?.let(recoveryHandler::removeCallbacks)
        val refresh = Runnable {
            pendingNetworkRefresh = null
            if (hasValidatedInternet()) {
                registrationRetryAttempt = 0
                pendingRegistrationRetry?.let(recoveryHandler::removeCallbacks)
                pendingRegistrationRetry = null
                runCatching { core.refreshRegisters() }
            }
        }
        pendingNetworkRefresh = refresh
        recoveryHandler.postDelayed(refresh, 1_500L)
    }

    private fun scheduleRegistrationRetry() {
        if (pendingRegistrationRetry != null || registrationRetryAttempt >= maxRegistrationRetries) return
        val delayMillis = 5_000L * (1L shl registrationRetryAttempt)
        registrationRetryAttempt += 1
        val retry = Runnable {
            pendingRegistrationRetry = null
            // Avoid retrying while Android has no active default network.
            if (hasValidatedInternet()) {
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
                NotificationChannel("tomos_sip_service", "SIP connection", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shows TomOS SIP registration and active-call status"
                    setShowBadge(false)
                }
            )
        }
        // The foreground service owns notification ID 1002. Update that same
        // notification instead of posting a second status icon or cancelling the
        // foreground notification when registration is temporarily unavailable.
        val title = when {
            activeCall -> "TomOS SIP • $callStatus"
            registered -> "TomOS SIP • Registered"
            else -> "TomOS SIP • Not registered"
        }
        val detail = if (activeCall) {
            if (callStartedAtMillis > 0L) activeCallName else "Call with $activeCallName"
        } else if (registered) "Your SIP account is registered and ready for calls"
        else "Set up or register a SIP account to make calls."
        val notification = NotificationCompat.Builder(appContext, "tomos_sip_service")
            .setSmallIcon(R.drawable.ic_notification_tomos)
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
        val notificationBuilder = NotificationCompat.Builder(appContext, "incoming_calls")
            .setSmallIcon(R.drawable.ic_notification_tomos)
            .setContentTitle("TomOS SIP • Incoming call")
            .setContentText(caller)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setAutoCancel(false)
            // IncomingEarlyMedia can follow IncomingReceived. Updating the same
            // notification must not restart its ringtone/vibration each time.
            .setOnlyAlertOnce(true)
            .setContentIntent(fullScreenIntent)
            .setFullScreenIntent(fullScreenIntent, true)

        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val callerPerson = Person.Builder()
                .setName(caller)
                .setImportant(true)
                .build()
            notificationBuilder
                .setStyle(NotificationCompat.CallStyle.forIncomingCall(
                    callerPerson, declineIntent, answerIntent
                ))
                .build()
        } else {
            notificationBuilder
                .addAction(android.R.drawable.sym_action_call, "Answer", answerIntent)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Decline", declineIntent)
                .build()
        }
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
    fun toggleHold() {
        val call = core.currentCall ?: run { callStatus = "No active call."; return }
        val result = when (call.state) {
            Call.State.Paused -> call.resume()
            Call.State.StreamsRunning -> call.pause()
            else -> {
                callStatus = "Wait until the call is connected before changing hold."
                return
            }
        }
        if (result != 0) {
            callStatus = if (call.state == Call.State.Paused) "Resume request failed. Please try again."
            else "Hold request failed. Please try again."
        }
        // The call-state listener is the source of truth for [held].
    }
    fun sendDtmf(digit: Char): Boolean {
        if (digit !in "0123456789*#ABCD".toSet()) return false
        val call = core.currentCall ?: run { callStatus = "No active call."; return false }
        if (call.state != Call.State.StreamsRunning) {
            callStatus = "Keypad tones are available when the call is connected."
            return false
        }
        val result = call.sendDtmf(digit)
        if (result != 0) callStatus = "Unable to send keypad tone ($result)."
        return result == 0
    }
    fun transfer(destination: String): String {
        return try {
            require(destination.isNotBlank()) { "Enter an extension or SIP address to transfer to." }
            val call = core.currentCall ?: throw IllegalStateException("There is no active call to transfer.")
            require(call.state == Call.State.StreamsRunning || call.state == Call.State.Paused) {
                "The call must be connected before it can be transferred."
            }
            val target = if (destination.contains("@")) destination.trim() else {
                val domain = core.defaultAccount?.params?.serverAddress?.domain
                    ?: throw IllegalStateException("SIP server is not configured.")
                "sip:" + destination.trim() + "@" + domain
            }
            val address = Factory.instance().createAddress(target)
                ?: throw IllegalArgumentException("Invalid transfer destination.")
            val result = call.transferTo(address)
            if (result == 0) "Transfer request sent to $destination."
            else "Transfer failed (Linphone error $result)."
        } catch (error: Exception) {
            error.message ?: "Unable to transfer this call."
        }.also { callStatus = it }
    }
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
    // 600dp follows Android adaptive window guidance: compact phones use bottom navigation,
    // while unfolded/tablet-sized windows use a navigation rail.
    val isExpandedLayout = LocalConfiguration.current.screenWidthDp >= 600
    val updateScope = rememberCoroutineScope()
    var updateDownloading by remember { mutableStateOf(false) }
    var updateDownloadedBytes by remember { mutableLongStateOf(0L) }
    var updateTotalBytes by remember { mutableLongStateOf(-1L) }
    var updateMessage by remember { mutableStateOf("Checking GitHub releases…") }
    var availableUpdate by remember { mutableStateOf<GitHubUpdate?>(null) }
    var updateCheckKey by remember { mutableIntStateOf(0) }
    var backupMessage by remember { mutableStateOf("Create a password-protected backup before reinstalling.") }
    var showExportPasswordDialog by remember { mutableStateOf(false) }
    var exportPassword by remember { mutableStateOf("") }
    var exportPasswordConfirm by remember { mutableStateOf("") }
    var showImportPasswordDialog by remember { mutableStateOf(false) }
    var importPassword by remember { mutableStateOf("") }
    var pendingEncryptedImport by remember { mutableStateOf<ByteArray?>(null) }
    var pendingExportBytes by remember { mutableStateOf<ByteArray?>(null) }
    var backupRemindersEnabled by remember { mutableStateOf(context.getSharedPreferences("tomos_sip", Context.MODE_PRIVATE).getBoolean("backup_reminders_enabled", false)) }
    LaunchedEffect(backupRemindersEnabled) {
        context.getSharedPreferences("tomos_sip", Context.MODE_PRIVATE).edit().putBoolean("backup_reminders_enabled", backupRemindersEnabled).apply()
        if (backupRemindersEnabled) BackupReminderScheduler.schedule(context) else BackupReminderScheduler.cancel(context)
    }
    var pendingBackup by remember { mutableStateOf<TomosBackupDocument?>(null) }
    val exportBackupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) {
            try {
                val bytes = pendingExportBytes ?: throw IllegalStateException("No encrypted backup is ready.")
                context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                    ?: throw IllegalStateException("Android couldn't open the selected backup file.")
                backupMessage = "Encrypted backup saved. Keep the password safe; it cannot be recovered."
            } catch (error: Exception) {
                backupMessage = "Backup failed: ${error.message ?: "could not save file"}"
            } finally {
                pendingExportBytes = null
            }
        }
    }
    val importBackupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                val input = context.contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException("Android couldn't open the selected file.")
                val bytes = input.use { stream ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    var total = 0
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= TomosBackupEncryption.MAX_ENCRYPTED_BYTES) { "Backup file is larger than 5 MB." }
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
                if (TomosBackupEncryption.isEncrypted(bytes)) {
                    pendingEncryptedImport = bytes
                    importPassword = ""
                    showImportPasswordDialog = true
                    backupMessage = "Encrypted backup selected. Enter its password to validate it."
                } else {
                    // Continue to support existing unencrypted JSON backups.
                    pendingBackup = TomosBackupCodec.decode(bytes.toString(Charsets.UTF_8))
                    backupMessage = "Legacy backup checked. Confirm below to replace the current contacts and call history."
                }
            } catch (error: Exception) {
                pendingBackup = null
                backupMessage = "Restore failed: ${error.message ?: "invalid backup file"}"
            }
        }
    }
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
        if (showExportPasswordDialog) {
            AlertDialog(
                onDismissRequest = { showExportPasswordDialog = false; exportPassword = ""; exportPasswordConfirm = "" },
                title = { Text("Encrypt backup") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Choose a password of at least 8 characters. You will need it to restore this backup after reinstalling.")
                        OutlinedTextField(exportPassword, { exportPassword = it }, label = { Text("Backup password") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), colors = fieldColors())
                        OutlinedTextField(exportPasswordConfirm, { exportPasswordConfirm = it }, label = { Text("Confirm password") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), colors = fieldColors())
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        try {
                            require(exportPassword.length >= 8) { "Use at least 8 characters." }
                            require(exportPassword == exportPasswordConfirm) { "The passwords do not match." }
                            val json = TomosBackupCodec.encode(engine.contacts, engine.history)
                            pendingExportBytes = TomosBackupEncryption.encrypt(json.toByteArray(Charsets.UTF_8), exportPassword.toCharArray())
                            showExportPasswordDialog = false
                            exportBackupLauncher.launch("tomos-sip-backup.tomos")
                        } catch (error: Exception) {
                            backupMessage = "Backup failed: ${error.message ?: "could not encrypt backup"}"
                        } finally {
                            exportPassword = ""
                            exportPasswordConfirm = ""
                        }
                    }) { Text("ENCRYPT & EXPORT", color = Cyan) }
                },
                dismissButton = { TextButton(onClick = { showExportPasswordDialog = false; exportPassword = ""; exportPasswordConfirm = "" }) { Text("CANCEL") } }
            )
        }
        if (showImportPasswordDialog) {
            AlertDialog(
                onDismissRequest = { showImportPasswordDialog = false; pendingEncryptedImport = null; importPassword = "" },
                title = { Text("Unlock encrypted backup") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Enter the password used when this backup was created.")
                        OutlinedTextField(importPassword, { importPassword = it }, label = { Text("Backup password") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), colors = fieldColors())
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        try {
                            val encrypted = pendingEncryptedImport ?: throw IllegalStateException("No encrypted backup is selected.")
                            val plaintext = TomosBackupEncryption.decrypt(encrypted, importPassword.toCharArray())
                            pendingBackup = TomosBackupCodec.decode(plaintext.toString(Charsets.UTF_8))
                            backupMessage = "Encrypted backup verified. Confirm below to restore contacts and call history."
                            showImportPasswordDialog = false
                            pendingEncryptedImport = null
                        } catch (error: Exception) {
                            backupMessage = "Could not unlock backup: ${error.message ?: "incorrect password or damaged file"}"
                        } finally {
                            importPassword = ""
                        }
                    }) { Text("UNLOCK BACKUP", color = Cyan) }
                },
                dismissButton = { TextButton(onClick = { showImportPasswordDialog = false; pendingEncryptedImport = null; importPassword = "" }) { Text("CANCEL") } }
            )
        }
        if (pendingBackup != null) {
            val backup = pendingBackup!!
            AlertDialog(
                onDismissRequest = { pendingBackup = null },
                title = { Text("Restore TomOS backup?") },
                text = { Text("This will replace ${engine.contacts.size} contacts and ${engine.history.size} call records with ${backup.contacts?.size ?: 0} contacts and ${backup.callHistory?.size ?: 0} call records from this backup. SIP accounts and passwords are not included or changed.") },
                confirmButton = {
                    TextButton(onClick = {
                        try {
                            engine.restoreBackup(backup)
                            backupMessage = "Restore complete. Contacts and call history have been restored."
                        } catch (error: Exception) {
                            backupMessage = "Restore failed: ${error.message ?: "could not apply backup"}"
                        }
                        pendingBackup = null
                    }) { Text("RESTORE", color = Pink) }
                },
                dismissButton = { TextButton(onClick = { pendingBackup = null }) { Text("CANCEL") } }
            )
        }
            Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = Void,
            bottomBar = {
                if (!isExpandedLayout) {
                    NavigationBar(containerColor = Panel, contentColor = Color.White) {
                        tabs.forEach { tab ->
                            NavigationBarItem(
                                selected = selectedTab == tab,
                                onClick = { selectedTab = tab },
                                icon = { val iconScale by animateFloatAsState(targetValue = if (selectedTab == tab) 1.12f else 1f, animationSpec = tween(180), label = "navigation-icon-scale"); Text(tabSymbols[tab] ?: "•", modifier = Modifier.scale(iconScale), fontSize = 19.sp) },
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
            }
        ) { innerPadding ->
        Row(Modifier.fillMaxSize().padding(innerPadding)) {
            if (isExpandedLayout) {
                NavigationRail(containerColor = Panel, contentColor = Color.White) {
                    tabs.forEach { tab ->
                        NavigationRailItem(
                            selected = selectedTab == tab,
                            onClick = { selectedTab = tab },
                            icon = { val iconScale by animateFloatAsState(targetValue = if (selectedTab == tab) 1.12f else 1f, animationSpec = tween(180), label = "navigation-icon-scale"); Text(tabSymbols[tab] ?: "•", modifier = Modifier.scale(iconScale), fontSize = 19.sp) },
                            label = { Text(tab) },
                            alwaysShowLabel = true,
                            colors = NavigationRailItemDefaults.colors(
                                selectedIconColor = Cyan, selectedTextColor = Cyan,
                                indicatorColor = Color(0xFF303044),
                                unselectedIconColor = Muted, unselectedTextColor = Muted
                            )
                        )
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxHeight()) {
        if (engine.activeCall) {
            ActiveCallScreen(engine = engine, elapsedSeconds = elapsedCallSeconds, modifier = Modifier.fillMaxSize())
        } else Column(
            Modifier.fillMaxWidth().widthIn(max = 1000.dp).align(Alignment.TopCenter)
                .background(Brush.verticalGradient(listOf(Color(0xFF111426), Void, Color(0xFF090A12))))
                .animateContentSize(animationSpec = tween(220))
                .verticalScroll(rememberScrollState()).padding(if (isExpandedLayout) 28.dp else 22.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    Modifier.size(48.dp)
                        .background(Panel, RoundedCornerShape(15.dp))
                        .border(1.dp, Pink.copy(alpha = 0.8f), RoundedCornerShape(15.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("T", color = Cyan, fontSize = 28.sp, fontWeight = FontWeight.Black)
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("TOM OS", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
                    Text("SIP COMMUNICATOR", color = Cyan, fontSize = 10.sp, letterSpacing = 1.8.sp)
                }
                Spacer(Modifier.weight(1f))
                Surface(
                    color = Panel,
                    shape = RoundedCornerShape(50),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF34394D))
                ) {
                    Text("v" + BuildConfig.VERSION_NAME, color = Muted, fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp))
                }
            }
            AnimatedContent(
                targetState = selectedTab,
                transitionSpec = {
                    (fadeIn(tween(220)) + slideInVertically(tween(220)) { it / 8 }) togetherWith
                        (fadeOut(tween(130)) + slideOutVertically(tween(130)) { -it / 12 })
                },
                label = "page-heading-transition"
            ) { tab ->
                val pageTitle = when (tab) {
                    "Dialler" -> "Make a call"
                    "Contacts" -> "Your contacts"
                    "History" -> "Call history"
                    "Settings" -> "App settings"
                    else -> "TomOS SIP"
                }
                val pageSubtitle = when (tab) {
                    "Dialler" -> "Your network. Your control."
                    "Contacts" -> "Keep the people you call close at hand."
                    "History" -> "Review your recent conversations."
                    "Settings" -> "Manage updates, accounts and preferences."
                    else -> "Your calls. Your control."
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(tab.uppercase(), color = Cyan, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                    Text(pageTitle, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    Text(pageSubtitle, color = Pink, fontSize = 14.sp)
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
            Card(
                modifier = Modifier.fillMaxWidth().border(
                    1.dp, (if (engine.registered) Cyan else Pink).copy(alpha = 0.28f), RoundedCornerShape(20.dp)
                ),
                colors = CardDefaults.cardColors(containerColor = Panel),
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("SIP ACCOUNT", color = Muted, fontSize = 10.sp,
                            fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp)
                        Spacer(Modifier.weight(1f))
                        Surface(
                            color = (if (engine.registered) Cyan else Pink).copy(alpha = 0.12f),
                            shape = RoundedCornerShape(50)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Box(Modifier.size(7.dp).background(
                                    if (engine.registered) Cyan else Pink, RoundedCornerShape(50)
                                ))
                                Text(if (engine.registered) "REGISTERED" else "NOT REGISTERED",
                                    color = if (engine.registered) Cyan else Pink,
                                    fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
                            }
                        }
                    }
                    Text(engine.status, color = Color.White, fontSize = 14.sp, lineHeight = 20.sp)
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("RECENT CALLS", color = Cyan, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                        Spacer(Modifier.weight(1f))
                        if (engine.history.isNotEmpty()) TextButton(onClick = { engine.clearCallHistory() }) {
                            Text("CLEAR ALL", color = Pink, fontSize = 11.sp)
                        }
                    }
                    if (engine.history.isEmpty()) Text("No calls yet", color = Muted)
                    engine.history.take(50).forEach { record ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(record.peer, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                val dateLabel = if (record.startedAt > 0L)
                                    SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(Date(record.startedAt))
                                    else "Imported history"
                                Text("${record.direction} • ${record.result} • $dateLabel", color = Muted, fontSize = 11.sp)
                                Text("Duration: ${record.durationSeconds / 60}m ${record.durationSeconds % 60}s", color = Muted, fontSize = 11.sp)
                            }
                            TextButton(onClick = { engine.callBack(record) }) { Text("CALL", color = Cyan) }
                            TextButton(onClick = { engine.deleteCallRecord(record.id) }) { Text("✕", color = Pink) }
                        }
                        Divider(color = Color(0xFF303448))
                    }
                }
            }
            }
            if (selectedTab == "Settings") {
            Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("DATA BACKUP & RESTORE", color = Cyan, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                    Text("Export contacts and call history as an encrypted backup, then restore it after reinstalling TomOS SIP. Older JSON backups can still be imported.", color = Muted, fontSize = 12.sp)
                    Text(backupMessage, color = Color.White, fontSize = 12.sp)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("WEEKLY BACKUP REMINDER", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Text("Get a notification to export a fresh backup. Your file is never written automatically.", color = Muted, fontSize = 11.sp)
                        }
                        Switch(checked = backupRemindersEnabled, onCheckedChange = { backupRemindersEnabled = it })
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { showExportPasswordDialog = true }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Void)) { Text("ENCRYPT & EXPORT") }
                        OutlinedButton(onClick = { importBackupLauncher.launch(arrayOf("application/json", "text/json", "application/octet-stream", "*/*")) }, modifier = Modifier.weight(1f)) { Text("IMPORT BACKUP") }
                    }
                    Text("AES-256-GCM encryption protects the backup with your password. SIP credentials are deliberately excluded. Keep both the file and password safe outside the app; a forgotten password cannot be recovered.", color = Muted, fontSize = 11.sp)
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
                                enabled = true,
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
    }
}

@Composable
private fun ActiveCallScreen(engine: TomSipEngine, elapsedSeconds: Int, modifier: Modifier = Modifier) {
    val isIncoming = engine.incomingCaller != null
    var showDialpad by remember { mutableStateOf(false) }
    var showTransfer by remember { mutableStateOf(false) }
    var dialledDigits by remember { mutableStateOf("") }
    var transferDestination by remember { mutableStateOf("") }
    var transferMessage by remember { mutableStateOf("") }
    val callPulse = rememberInfiniteTransition(label = "active-call-pulse")
        .animateFloat(initialValue = 1f, targetValue = 1.045f,
            animationSpec = infiniteRepeatable(tween(1100), repeatMode = RepeatMode.Reverse),
            label = "active-call-avatar-scale")
    Column(
        modifier.background(Brush.verticalGradient(listOf(Void, Color(0xFF111326))))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(22.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("TOMOS SIP", color = Cyan, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
            Spacer(Modifier.height(24.dp))
            Box(Modifier.size(112.dp).scale(callPulse.value).background(Panel, RoundedCornerShape(56.dp))
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
            Column(verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    CallControl("MUTE", engine.muted, "🎙") { engine.toggleMute() }
                    CallControl("SPEAKER", engine.speaker, "🔊") { engine.toggleSpeaker() }
                    CallControl(if (engine.held) "RESUME" else "HOLD", engine.held, "Ⅱ") { engine.toggleHold() }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = { showDialpad = !showDialpad; if (showDialpad) showTransfer = false },
                        modifier = Modifier.weight(1f).height(48.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Cyan)
                    ) { Text(if (showDialpad) "HIDE KEYPAD" else "KEYPAD") }
                    OutlinedButton(
                        onClick = { showTransfer = !showTransfer; if (showTransfer) showDialpad = false },
                        modifier = Modifier.weight(1f).height(48.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Cyan)
                    ) { Text(if (showTransfer) "CLOSE TRANSFER" else "TRANSFER") }
                }
                if (showDialpad) {
                    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("IN-CALL KEYPAD", color = Cyan, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                            Text(dialledDigits.ifBlank { "Enter digits to send DTMF tones" }, color = Color.White, fontSize = 18.sp)
                            listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("*", "0", "#")).forEach { keys ->
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    keys.forEach { key ->
                                        OutlinedButton(
                                            onClick = { if (engine.sendDtmf(key.single())) dialledDigits += key },
                                            modifier = Modifier.weight(1f).height(48.dp),
                                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                                            shape = RoundedCornerShape(12.dp)
                                        ) { Text(key, fontSize = 20.sp, fontWeight = FontWeight.SemiBold) }
                                    }
                                }
                            }
                            TextButton(onClick = { dialledDigits = "" }) { Text("CLEAR DISPLAY", color = Pink) }
                        }
                    }
                }
                if (showTransfer) {
                    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("BLIND CALL TRANSFER", color = Cyan, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                            Text("Send the caller to an extension or SIP address.", color = Muted, fontSize = 12.sp)
                            OutlinedTextField(
                                value = transferDestination,
                                onValueChange = { transferDestination = it; transferMessage = "" },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Extension or SIP address") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                                colors = fieldColors()
                            )
                            if (transferMessage.isNotBlank()) {
                                Text(transferMessage, color = if (transferMessage.startsWith("Transfer request sent")) Cyan else Pink, fontSize = 12.sp)
                            }
                            Button(
                                onClick = { transferMessage = engine.transfer(transferDestination) },
                                modifier = Modifier.fillMaxWidth().height(50.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Void),
                                shape = RoundedCornerShape(14.dp)
                            ) { Text("TRANSFER CALL", fontWeight = FontWeight.Bold) }
                        }
                    }
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
