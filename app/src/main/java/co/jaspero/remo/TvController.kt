package co.jaspero.remo

import android.content.Context
import android.os.Build
import co.jaspero.remo.data.BuiltInPresets
import co.jaspero.remo.data.Preset
import co.jaspero.remo.data.SavedTv
import co.jaspero.remo.data.Settings
import co.jaspero.remo.data.Store
import co.jaspero.remo.tv.ClientIdentity
import co.jaspero.remo.tv.Keys
import co.jaspero.remo.tv.NotPairedException
import co.jaspero.remo.tv.PairingSession
import co.jaspero.remo.tv.RemoteSession
import co.jaspero.remo.tv.TvDiscovery
import co.jaspero.remo.tv.TvIdentityChangedException
import co.jaspero.remo.tv.WrongCodeException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

/** App-wide owner of the saved TVs, the live connection and pairing. Lives as long as the process. */
class TvController(context: Context) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val store = Store(app)
    val discovery = TvDiscovery(app)

    sealed interface Conn {
        data object NoTv : Conn
        data object Connecting : Conn
        data object Connected : Conn
        data object NeedsPairing : Conn
        data class Unreachable(val message: String) : Conn
    }

    sealed interface Pairing {
        data object Idle : Pairing
        data class Starting(val name: String) : Pairing
        data class EnterCode(val name: String, val error: String? = null, val verifying: Boolean = false) : Pairing
        data class Failed(val name: String, val message: String) : Pairing
    }

    private val identity: Deferred<ClientIdentity> = scope.async(Dispatchers.Default) {
        ClientIdentity.loadOrCreate(File(app.filesDir, "client.p12"), "Remo on ${Build.MODEL}")
    }

    private val _tvs = MutableStateFlow(store.tvs())
    val tvs: StateFlow<List<SavedTv>> = _tvs.asStateFlow()

    private val _current = MutableStateFlow(store.currentTvName?.let { n -> _tvs.value.find { it.name == n } } ?: _tvs.value.firstOrNull())
    val current: StateFlow<SavedTv?> = _current.asStateFlow()

    private val _presets = MutableStateFlow(store.presets())
    val presets: StateFlow<List<Preset>> = _presets.asStateFlow()

    private val _settings = MutableStateFlow(store.settings())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    private val _conn = MutableStateFlow<Conn>(if (_current.value == null) Conn.NoTv else Conn.Connecting)
    val conn: StateFlow<Conn> = _conn.asStateFlow()

    private val _pairing = MutableStateFlow<Pairing>(Pairing.Idle)
    val pairing: StateFlow<Pairing> = _pairing.asStateFlow()

    private val session = MutableStateFlow<RemoteSession?>(null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val tvState: StateFlow<RemoteSession.State> = session
        .flatMapLatest { it?.state ?: flowOf(RemoteSession.State()) }
        .stateIn(scope, SharingStarted.Eagerly, RemoteSession.State())

    private var connectJob: Job? = null
    private var idleJob: Job? = null
    private var pairingSession: PairingSession? = null
    private var pairingHost: String? = null
    private var foreground = false

    // ---- Lifecycle ----------------------------------------------------------------------------

    fun onForeground() {
        foreground = true
        idleJob?.cancel()
        ensureConnected()
    }

    /** Keeps the connection for a little while after leaving the app, so switching back is instant. */
    fun onBackground() {
        foreground = false
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(20_000)
            disconnect()
        }
    }

    fun ensureConnected() {
        if (connectJob?.isActive == true) return
        val tv = _current.value ?: run { _conn.value = Conn.NoTv; return }
        connectJob = scope.launch { connectLoop(tv) }
    }

    fun retry() {
        disconnect()
        ensureConnected()
    }

    private fun disconnect() {
        connectJob?.cancel()
        connectJob = null
        session.value?.close()
        session.value = null
        if (_conn.value == Conn.Connected) _conn.value = Conn.Connecting
    }

    private suspend fun connectLoop(initial: SavedTv) {
        var tv = initial
        var failures = 0
        while (scope.isActive) {
            if (session.value?.isOpen != true) _conn.value = Conn.Connecting
            val s = RemoteSession(tv.host, identity.await(), tv.pin.ifEmpty { null }, scope)
            try {
                s.open()
                session.value = s
                _conn.value = Conn.Connected
                failures = 0
                s.closed.await()
                session.value = null
                if (!foreground) {
                    _conn.value = Conn.Connecting
                    return
                }
            } catch (e: NotPairedException) {
                _conn.value = Conn.NeedsPairing
                return
            } catch (e: TvIdentityChangedException) {
                _conn.value = Conn.NeedsPairing
                return
            } catch (e: IOException) {
                failures++
                // The TV may have a new address: look it up by name before giving up on this round.
                if (failures == 1 || failures % 4 == 0) {
                    val host = discovery.findByName(tv.name, 2500)
                    if (host != null && host != tv.host) {
                        tv = tv.copy(host = host)
                        updateTv(tv)
                        continue
                    }
                }
                if (failures >= 2) _conn.value = Conn.Unreachable("Can't reach ${tv.name}. Is it on and on the same Wi-Fi?")
                if (!foreground && failures >= 2) return
                delay(minOf(500L * failures, 4000L))
            }
        }
    }

    private fun updateTv(tv: SavedTv) {
        _tvs.value = _tvs.value.map { if (it.name == tv.name) tv else it }
        store.saveTvs(_tvs.value)
        if (_current.value?.name == tv.name) _current.value = tv
    }

    // ---- TVs ----------------------------------------------------------------------------------

    fun select(tv: SavedTv) {
        disconnect()
        _current.value = tv
        store.currentTvName = tv.name
        ensureConnected()
    }

    fun forget(tv: SavedTv) {
        _tvs.value = _tvs.value.filterNot { it.name == tv.name }
        store.saveTvs(_tvs.value)
        if (_current.value?.name == tv.name) {
            disconnect()
            _current.value = _tvs.value.firstOrNull()
            store.currentTvName = _current.value?.name
            if (_current.value == null) _conn.value = Conn.NoTv else ensureConnected()
        }
    }

    // ---- Pairing ------------------------------------------------------------------------------

    fun startPairing(name: String, host: String) {
        cancelPairing()
        _pairing.value = Pairing.Starting(name)
        pairingHost = host
        scope.launch {
            val p = PairingSession(host, identity.await(), "Remo (${Build.MODEL})")
            pairingSession = p
            try {
                p.start()
                _pairing.value = Pairing.EnterCode(name)
            } catch (e: Exception) {
                p.close()
                if (pairingSession === p) {
                    pairingSession = null
                    _pairing.value = Pairing.Failed(name, "Couldn't start pairing with $name: ${e.message ?: e.javaClass.simpleName}")
                }
            }
        }
    }

    fun submitCode(code: String) {
        val state = _pairing.value as? Pairing.EnterCode ?: return
        val p = pairingSession ?: return
        val host = pairingHost ?: return
        _pairing.value = state.copy(verifying = true, error = null)
        scope.launch {
            try {
                val pin = p.finish(code)
                p.close()
                pairingSession = null
                val tv = SavedTv(state.name, host, pin)
                _tvs.value = _tvs.value.filterNot { it.name == tv.name } + tv
                store.saveTvs(_tvs.value)
                _pairing.value = Pairing.Idle
                select(tv)
            } catch (e: WrongCodeException) {
                _pairing.value = state.copy(verifying = false, error = e.message)
            } catch (e: Exception) {
                p.close()
                pairingSession = null
                _pairing.value = Pairing.Failed(state.name, "Pairing failed: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    fun cancelPairing() {
        pairingSession?.close()
        pairingSession = null
        _pairing.value = Pairing.Idle
    }

    // ---- Commands -----------------------------------------------------------------------------

    private fun withSession(block: (RemoteSession) -> Unit) {
        val s = session.value
        if (s != null && s.isOpen) block(s) else ensureConnected()
    }

    fun key(code: Int, direction: RemoteSession.Direction = RemoteSession.Direction.SHORT) = withSession { it.key(code, direction) }

    fun launch(preset: Preset) = withSession { it.launch(preset.link) }

    /**
     * Brings the TV's text field from [old] to [new]: deletes what changed at the end and inserts
     * the rest. Plain keys are used where they can type the text and no input session is open.
     */
    fun typeDiff(old: String, new: String) = withSession { s ->
        val common = old.commonPrefixWith(new).length
        repeat(old.length - common) { s.key(Keys.DEL) }
        val insert = new.substring(common)
        if (insert.isEmpty()) return@withSession
        if (s.hasImeTarget) {
            s.insertText(insert)
        } else {
            val pending = StringBuilder()
            for (c in insert) {
                val k = Keys.forChar(c)
                if (k != null) {
                    if (pending.isNotEmpty()) { s.insertText(pending.toString()); pending.clear() }
                    s.key(k)
                } else {
                    pending.append(c)
                }
            }
            if (pending.isNotEmpty()) s.insertText(pending.toString())
        }
    }

    // ---- Presets & settings ------------------------------------------------------------------

    fun addPreset(name: String, target: String) {
        _presets.value = _presets.value + BuiltInPresets.custom(name, target)
        store.savePresets(_presets.value)
    }

    fun removePreset(p: Preset) {
        _presets.value = _presets.value.filterNot { it.id == p.id }
        store.savePresets(_presets.value)
    }

    fun restoreDefaultPresets() {
        val custom = _presets.value.filterNot { it.builtIn }
        _presets.value = BuiltInPresets.all + custom
        store.savePresets(_presets.value)
    }

    fun updateSettings(transform: (Settings) -> Settings) {
        _settings.value = transform(_settings.value)
        store.saveSettings(_settings.value)
    }
}
