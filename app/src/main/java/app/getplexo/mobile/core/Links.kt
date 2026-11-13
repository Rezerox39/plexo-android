package app.getplexo.mobile.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

/** Live state of one physical link (radio). */
data class LinkState(
    val enabled: Boolean = false,
    val connected: Boolean = false,
    val validated: Boolean = false,
    val metered: Boolean = false,
    val downstreamKbps: Int = 0,
    val note: String? = null,
    /**
     * Kernel interface behind the network, e.g. wlan0, rmnet_data0, bt-pan, eth0.
     * Two links can receive different [android.net.Network] handles for one interface;
     * comparing this keeps the app from counting the same pipe twice.
     */
    val networkKey: String? = null,
)

/**
 * One network radio we can pull bytes from.
 *
 * Android only ever routes new traffic over the *default* network. To use a second
 * radio at the same time we have to ask ConnectivityManager for a network of that
 * transport ourselves, then bind every socket we open to the Network object we get
 * back. That is the whole trick behind Plexo on mobile.
 *
 * [transports] are OR'd by the platform: a network matches if it has any one of them.
 * That is how the wired link covers both classic Ethernet adapters and USB networks.
 */
class Link(
    val id: String,
    val title: String,
    val hint: String,
    private val transports: IntArray,
    val color: Long,
    private val cm: ConnectivityManager,
    initiallyEnabled: Boolean,
    private val missingNote: String,
) {
    private val _state = MutableStateFlow(LinkState(enabled = initiallyEnabled))
    val state: StateFlow<LinkState> = _state.asStateFlow()

    private val handler = Handler(Looper.getMainLooper())
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var enabled = initiallyEnabled
    private var retryPending = false
    private var retryDelayMs = INITIAL_RETRY_MS

    @Volatile
    private var network: Network? = null

    init {
        if (enabled) request()
    }

    fun network(): Network? = network

    /** Interface name reported by the OS, or null while the link has no network. */
    val networkKey: String? get() = _state.value.networkKey

    /** True when the user wants this link and the OS has handed us a live network for it. */
    val usable: Boolean get() = enabled && network != null

    fun setEnabled(on: Boolean) {
        if (on == enabled) return
        enabled = on
        val cb = callback
        callback = null
        network = null
        cb?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        handler.removeCallbacksAndMessages(null)
        retryPending = false
        if (on) {
            retryDelayMs = INITIAL_RETRY_MS
            _state.value = _state.value.copy(enabled = true, connected = false, note = null)
            request()
        } else {
            _state.value = LinkState(enabled = false)
        }
    }

    private fun request() {
        if (!enabled || network != null) return

        val builder = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        transports.distinct().forEach { builder.addTransportType(it) }
        val request = builder.build()

        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                this@Link.network = network
                retryDelayMs = INITIAL_RETRY_MS
                _state.value = _state.value.copy(
                    enabled = true,
                    connected = true,
                    note = null,
                    networkKey = keyFor(network),
                )
            }

            override fun onCapabilitiesChanged(
                network: Network,
                caps: NetworkCapabilities,
            ) {
                this@Link.network = network
                retryDelayMs = INITIAL_RETRY_MS
                _state.value = _state.value.copy(
                    enabled = true,
                    connected = true,
                    validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                    metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
                    downstreamKbps = caps.linkDownstreamBandwidthKbps,
                    note = null,
                    networkKey = keyFor(network) ?: _state.value.networkKey,
                )
            }

            override fun onLost(network: Network) {
                if (this@Link.network == network) this@Link.network = null
                _state.value = _state.value.copy(
                    connected = false,
                    validated = false,
                    downstreamKbps = 0,
                    networkKey = null,
                )
                // The request itself stays alive, so Android will call onAvailable again
                // if this transport comes back.
            }

            override fun onUnavailable() {
                _state.value = _state.value.copy(
                    enabled = true,
                    connected = false,
                    note = missingNote,
                )
                scheduleRetry()
            }
        }

        callback = cb
        try {
            // A timeout is only applied to the first match; after that the callback
            // stays registered for as long as we keep it.
            cm.requestNetwork(request, cb, REQUEST_TIMEOUT_MS)
        } catch (e: SecurityException) {
            _state.value = _state.value.copy(enabled = true, note = "Permission denied: ${e.message}")
        }
    }

    private fun keyFor(network: Network): String? =
        runCatching { cm.getLinkProperties(network)?.interfaceName }.getOrNull()
            ?: network.toString()

    private fun scheduleRetry() {
        if (retryPending || !enabled || network != null) return
        retryPending = true
        val delay = retryDelayMs
        // An adapter that is not plugged in will not appear for a while: back off so a
        // forgotten switch does not wake the system every 20 seconds forever.
        retryDelayMs = (retryDelayMs * 2).coerceAtMost(MAX_RETRY_MS)
        handler.postDelayed({
            retryPending = false
            if (enabled && network == null) request()
        }, delay)
    }

    fun release() {
        enabled = false
        val cb = callback
        callback = null
        network = null
        cb?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        handler.removeCallbacksAndMessages(null)
    }

    private companion object {
        const val REQUEST_TIMEOUT_MS = 20_000
        const val INITIAL_RETRY_MS = 20_000L
        const val MAX_RETRY_MS = 5 * 60_000L
    }
}

/** Owns the radios and remembers which ones the user switched on. */
class LinkRegistry(context: Context) {

    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("plexo_links", Context.MODE_PRIVATE)
    private val cm = app.getSystemService(ConnectivityManager::class.java)

    val links: List<Link> = listOf(
        Link(
            id = "wifi",
            title = "Wi-Fi",
            hint = "Your wireless router",
            transports = intArrayOf(NetworkCapabilities.TRANSPORT_WIFI),
            color = 0xFF34D399,
            cm = cm,
            initiallyEnabled = prefs.getBoolean("wifi", true),
            missingNote = "Not connected to a Wi-Fi network.",
        ),
        Link(
            id = "cellular",
            title = "Mobile data",
            hint = "Carrier connection",
            transports = intArrayOf(NetworkCapabilities.TRANSPORT_CELLULAR),
            color = 0xFF818CF8,
            cm = cm,
            initiallyEnabled = prefs.getBoolean("cellular", false),
            missingNote = "No mobile data. Check mobile data is on, and enable Developer options › Mobile data always active.",
        ),
        Link(
            id = "ethernet",
            title = "Wired / USB",
            hint = "Ethernet adapter or USB-C dock",
            transports = intArrayOf(NetworkCapabilities.TRANSPORT_ETHERNET) +
                // TRANSPORT_USB exists from Android 12; the constant is inlined, but older
                // releases reject transport bits they do not know.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    intArrayOf(NetworkCapabilities.TRANSPORT_USB)
                } else {
                    intArrayOf()
                },
            color = 0xFFF59E0B,
            cm = cm,
            initiallyEnabled = prefs.getBoolean("ethernet", true),
            missingNote = "No wired adapter. Plug in a USB/OTG Ethernet adapter or a USB-C dock.",
        ),
        Link(
            id = "bluetooth",
            title = "Bluetooth",
            hint = "PAN from a paired phone",
            transports = intArrayOf(NetworkCapabilities.TRANSPORT_BLUETOOTH),
            color = 0xFF38BDF8,
            cm = cm,
            initiallyEnabled = prefs.getBoolean("bluetooth", false),
            missingNote = "No Bluetooth network. Pair a phone that shares its connection, then turn on internet access for it in Bluetooth settings.",
        ),
    )

    fun link(id: String): Link? = links.firstOrNull { it.id == id }

    fun setEnabled(id: String, on: Boolean) {
        link(id)?.setEnabled(on)
        prefs.edit().putBoolean(id, on).apply()
    }

    /**
     * Links that can carry traffic right now.
     *
     * Two links can be handed different [Network] handles that sit on the same kernel
     * interface (a USB dock can look like both Ethernet and USB). Downloading over both
     * would not add bandwidth, so the later link is left out and the UI says why.
     */
    fun usable(): List<Link> {
        val seen = HashSet<String>()
        return links.filter { link ->
            val key = link.networkKey
            link.usable && key != null && seen.add(key)
        }
    }

    fun release() = links.forEach { it.release() }
}

/**
 * OkHttp socket factory that pins every TCP connection to one Network.
 *
 * API 30+ has Network.getSocketFactory(); on older releases we bind manually before
 * connect. Either way the kernel keeps using that radio even while another network
 * is the system default.
 */
class BoundSocketFactory(private val network: Network) : SocketFactory() {

    private val delegate: SocketFactory? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) network.socketFactory else null

    override fun createSocket(): Socket {
        val factory = delegate
        if (factory != null) return factory.createSocket()
        val socket = Socket()
        network.bindSocket(socket)
        return socket
    }

    override fun createSocket(host: String, port: Int): Socket =
        createSocket().apply { connect(InetSocketAddress(host, port)) }

    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        createSocket(host, port)

    override fun createSocket(address: InetAddress, port: Int): Socket =
        createSocket().apply { connect(InetSocketAddress(address, port)) }

    override fun createSocket(
        address: InetAddress,
        port: Int,
        localAddress: InetAddress,
        localPort: Int,
    ): Socket = createSocket(address, port)
}

/** DNS lookups through the same network, so split-DNS and captive links resolve correctly. */
private class NetworkDns(private val network: Network) : Dns {
    override fun lookup(hostname: String): List<InetAddress> =
        network.getAllByName(hostname).toList()
}

fun clientFor(network: Network): OkHttpClient = OkHttpClient.Builder()
    .socketFactory(BoundSocketFactory(network))
    .dns(NetworkDns(network))
    .connectTimeout(20, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .writeTimeout(30, TimeUnit.SECONDS)
    .retryOnConnectionFailure(true)
    .build()
