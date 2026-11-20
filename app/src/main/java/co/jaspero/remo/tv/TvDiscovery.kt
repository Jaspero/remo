package co.jaspero.remo.tv

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Finds Google TV / Android TV devices on the Wi-Fi via mDNS (_androidtvremote2._tcp). */
class TvDiscovery(context: Context) {
    data class Found(val name: String, val host: String)

    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val resolveLock = Mutex()
    private var listener: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var users = 0

    private val _found = MutableStateFlow<List<Found>>(emptyList())
    val found: StateFlow<List<Found>> = _found.asStateFlow()

    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()

    fun start() {
        if (users++ > 0) return
        multicastLock = wifi.createMulticastLock("remo-discovery").apply {
            setReferenceCounted(false)
            runCatching { acquire() }
        }
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) { _searching.value = true }
            override fun onDiscoveryStopped(serviceType: String) { _searching.value = false }
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { _searching.value = false }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onServiceFound(info: NsdServiceInfo) {
                scope.launch { resolve(info)?.let { add(it) } }
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                _found.update { list -> list.filterNot { it.name == info.serviceName } }
            }
        }
        listener = l
        runCatching { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l) }
    }

    fun stop() {
        if (users == 0 || --users > 0) return
        listener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        listener = null
        runCatching { multicastLock?.release() }
        multicastLock = null
        _searching.value = false
    }

    /** Looks for a TV by its name, for when its address changed since it was paired. */
    suspend fun findByName(name: String, timeoutMs: Long): String? {
        start()
        try {
            return withTimeoutOrNull(timeoutMs) {
                found.first { list -> list.any { it.name == name } }.first { it.name == name }.host
            }
        } finally {
            stop()
        }
    }

    private fun add(f: Found) = _found.update { list -> (list.filterNot { it.name == f.name } + f).sortedBy { it.name } }

    /** The old resolve API handles one request at a time, so requests are queued. */
    @Suppress("DEPRECATION")
    private suspend fun resolve(info: NsdServiceInfo): Found? = resolveLock.withLock {
        repeat(3) { attempt ->
            val result = withTimeoutOrNull(4000) {
                suspendCancellableCoroutine<Result<NsdServiceInfo>> { cont ->
                    nsd.resolveService(info, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(si: NsdServiceInfo, errorCode: Int) {
                            if (cont.isActive) cont.resume(Result.failure(RuntimeException("resolve $errorCode")))
                        }
                        override fun onServiceResolved(si: NsdServiceInfo) {
                            if (cont.isActive) cont.resume(Result.success(si))
                        }
                    })
                }
            }
            val si = result?.getOrNull()
            val host = si?.let(::ipv4) ?: si?.host?.hostAddress
            if (si != null && host != null) return@withLock Found(si.serviceName, host)
            delay(300L * (attempt + 1))
        }
        null
    }

    private fun ipv4(si: NsdServiceInfo): String? {
        val addrs = if (android.os.Build.VERSION.SDK_INT >= 34) si.hostAddresses else listOfNotNull(si.host)
        return addrs.firstOrNull { it is java.net.Inet4Address }?.hostAddress
    }

    companion object {
        const val SERVICE_TYPE = "_androidtvremote2._tcp."
    }
}
