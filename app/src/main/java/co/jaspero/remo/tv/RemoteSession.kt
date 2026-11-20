package co.jaspero.remo.tv

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.security.cert.X509Certificate
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket

/** The TV doesn't know this phone (never paired, or it forgot): pairing is needed. */
class NotPairedException : IOException("The TV doesn't recognise this phone. Pair again.")

/** The device at the saved address isn't the TV that was paired. */
class TvIdentityChangedException : IOException("This TV's identity changed. Pair again.")

/**
 * One connection to the TV's remote service (port 6466). The TV pings every 5 seconds; if nothing
 * arrives for 16 seconds the connection is treated as dead.
 */
class RemoteSession(
    private val host: String,
    private val identity: ClientIdentity,
    private val expectedPin: String?,
    private val scope: CoroutineScope,
    private val port: Int = PORT,
) {
    data class Volume(val level: Int, val max: Int, val muted: Boolean)
    data class TextField(val value: String, val label: String)

    data class State(
        val ready: Boolean = false,
        val isOn: Boolean? = null,
        val currentApp: String = "",
        val volume: Volume? = null,
        val model: String = "",
        val vendor: String = "",
        /** Set while the TV has a text field open and wants a keyboard. */
        val textField: TextField? = null,
    )

    enum class Direction(val wire: Int) { START_LONG(1), END_LONG(2), SHORT(3) }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** Completes with the reason when the connection ends. */
    val closed = CompletableDeferred<Throwable>()

    private val outbox = Channel<ByteArray>(Channel.UNLIMITED)
    private var socket: SSLSocket? = null
    private val jobs = mutableListOf<Job>()
    private val configured = CompletableDeferred<Unit>()
    private var activeFeatures = FEATURES

    @Volatile private var imeCounter = 0
    @Volatile private var fieldCounter = 0
    @Volatile private var countersFromInject = false

    val isOpen get() = !closed.isCompleted

    /**
     * Connects and waits until the TV has sent its configuration, so a TV that doesn't know us is
     * reported as [NotPairedException] here rather than later.
     */
    suspend fun open() {
        withContext(Dispatchers.IO) {
            val s = identity.sslContext.socketFactory.createSocket() as SSLSocket
            socket = s
            try {
                s.tcpNoDelay = true
                s.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                s.soTimeout = READ_TIMEOUT_MS
                s.startHandshake()
            } catch (e: SSLException) {
                close(e)
                throw NotPairedException()
            } catch (e: IOException) {
                close(e)
                throw e
            }
            if (expectedPin != null) {
                val cert = s.session.peerCertificates.firstOrNull() as? X509Certificate
                if (cert == null || ClientIdentity.pin(cert) != expectedPin) {
                    close(TvIdentityChangedException())
                    throw TvIdentityChangedException()
                }
            }
        }
        val s = socket!!
        jobs += scope.launch(Dispatchers.IO) { readLoop(s) }
        jobs += scope.launch(Dispatchers.IO) { writeLoop(s) }

        val ok = withTimeoutOrNull(HANDSHAKE_TIMEOUT_MS) {
            select {
                configured.onAwait { true }
                closed.onAwait { false }
            }
        }
        when (ok) {
            true -> Unit
            false -> {
                val reason = closed.await()
                // Rejected client certificates surface as an SSL error or an immediate hang-up.
                throw if (reason is SSLException || reason is EOFException) NotPairedException()
                else reason as? IOException ?: IOException(reason)
            }
            null -> {
                close(IOException("The TV didn't answer"))
                throw IOException("The TV didn't answer")
            }
        }
    }

    fun close(reason: Throwable = IOException("Closed")) {
        if (!closed.complete(reason)) return
        outbox.close()
        runCatching { socket?.close() }
        jobs.forEach { it.cancel() }
    }

    // ---- Commands -------------------------------------------------------------------------

    fun key(keyCode: Int, direction: Direction = Direction.SHORT) = send {
        message(F_KEY_INJECT) { int(1, keyCode); int(2, direction.wire) }
    }

    /** Opens an app by link: an https deep link, an app scheme, or market://launch?id=<package>. */
    fun launch(link: String) = send { message(F_APP_LINK) { string(1, link) } }

    /** Inserts text at the cursor of the focused field on the TV. */
    fun insertText(text: String) {
        if (text.isEmpty()) return
        send {
            message(F_IME_BATCH_EDIT) {
                int(1, imeCounter)
                int(2, fieldCounter)
                message(3) {
                    int(1, 1)
                    message(2) { int(1, text.length); int(2, text.length); string(3, text) }
                }
            }
        }
    }

    /** True once the TV has told us which text field is focused, so [insertText] is addressed correctly. */
    val hasImeTarget get() = imeCounter != 0 || fieldCounter != 0

    // ---- Wire --------------------------------------------------------------------------------

    private fun send(block: ProtoWriter.() -> Unit) {
        if (!isOpen) return
        outbox.trySend(ProtoWriter.frame(proto(block)))
    }

    private suspend fun writeLoop(s: SSLSocket) {
        try {
            val out = s.outputStream
            for (frame in outbox) {
                out.write(frame)
                out.flush()
            }
        } catch (e: Exception) {
            close(e)
        }
    }

    private fun readLoop(s: SSLSocket) {
        try {
            val input = s.inputStream
            while (true) handle(ProtoMessage.readDelimited(input))
        } catch (e: Exception) {
            close(e)
        }
    }

    private fun handle(msg: ProtoMessage) {
        msg.message(F_CONFIGURE)?.let { cfg ->
            val info = cfg.message(2)
            activeFeatures = FEATURES and cfg.int(1)
            _state.update { it.copy(model = info?.string(1).orEmpty(), vendor = info?.string(2).orEmpty()) }
            send {
                message(F_CONFIGURE) {
                    int(1, activeFeatures)
                    message(2) {
                        int(3, 1)
                        string(4, "1")
                        string(5, "atvremote")
                        string(6, "1.0.0")
                    }
                }
            }
            configured.complete(Unit)
        }
        if (msg.has(F_SET_ACTIVE)) send { message(F_SET_ACTIVE) { int(1, activeFeatures) } }
        msg.message(F_PING_REQUEST)?.let { ping -> send { message(F_PING_RESPONSE) { int(1, ping.int(1)) } } }
        msg.message(F_IME_KEY_INJECT)?.let { inject ->
            val app = inject.message(1)
            imeCounter = app?.int(1) ?: 0
            fieldCounter = inject.message(2)?.int(1) ?: 0
            countersFromInject = imeCounter != 0 || fieldCounter != 0
            val pkg = app?.string(12).orEmpty()
            if (pkg.isNotEmpty()) _state.update { it.copy(currentApp = pkg) }
        }
        msg.message(F_IME_SHOW_REQUEST)?.let { show ->
            val field = show.message(2)
            if (field != null) {
                fieldCounter = field.int(1)
                _state.update { it.copy(textField = TextField(field.string(2), field.string(6))) }
            } else {
                _state.update { it.copy(textField = null) }
            }
        }
        msg.message(F_IME_BATCH_EDIT)?.let { edit ->
            if (!countersFromInject) {
                imeCounter = edit.int(1)
                fieldCounter = edit.int(2)
            }
        }
        msg.message(F_SET_VOLUME)?.let { v ->
            _state.update { it.copy(volume = Volume(v.int(7), v.int(6), v.bool(8))) }
        }
        msg.message(F_REMOTE_START)?.let { start ->
            _state.update { it.copy(ready = true, isOn = start.bool(1)) }
        }
    }

    companion object {
        const val PORT = 6466
        private const val CONNECT_TIMEOUT_MS = 3000
        private const val READ_TIMEOUT_MS = 16000
        private const val HANDSHAKE_TIMEOUT_MS = 6000L

        // Features: PING 1, KEY 2, IME 4, POWER 32, VOLUME 64, APP_LINK 512. Voice (8) is left out
        // on purpose: it isn't used, and asking for it makes some TVs set up audio routing.
        const val FEATURES = 1 or 2 or 4 or 32 or 64 or 512

        private const val F_CONFIGURE = 1
        private const val F_SET_ACTIVE = 2
        private const val F_PING_REQUEST = 8
        private const val F_PING_RESPONSE = 9
        private const val F_KEY_INJECT = 10
        private const val F_IME_KEY_INJECT = 20
        private const val F_IME_BATCH_EDIT = 21
        private const val F_IME_SHOW_REQUEST = 22
        private const val F_REMOTE_START = 40
        private const val F_SET_VOLUME = 50
        private const val F_APP_LINK = 90
    }
}
