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
)

/**
 * One network radio we can pull bytes from.
 *
 * Android only ever routes new traffic over the *default* network. To use a second
 * radio at the same time we have to ask ConnectivityManager for a network of that
 * transport ourselves, then bind every socket we open to the Network object we get
 * back. That is the whole trick behind Plexo on mobile.
 */
class Link(
    val id: String,
    val title: String,
    val hint: String,
    private val transport: Int,
    val color: Long,
    private val cm: ConnectivityManager,
    initiallyEnabled: Boolean,
) {
    private val _state = MutableStateFlow(LinkState(enabled = initiallyEnabled))
    val state: StateFlow<LinkState> = _state.asStateFlow()

    private val handler = Handler(Looper.getMainLooper())
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var enabled = initiallyEnabled
    private var retryPending = false

    @Volatile
    private var network: Network? = null

    init {
        if (enabled) request()
    }

    fun network(): Network? = network

    /** True when the user wants this link and the OS has handed us a live network for it. */
    val usable: Boolean get() = enabled && network != null

    fun setEnabled(on: Boolean) {
        if (on == enabled) return
        enabled = on
        val cb = callback
        callback = null
        network = null
        cb?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        if (on) {
            request()
        } else {
            _state.value = LinkState(enabled = false)
        }
    }

    private fun request() {
        if (!enabled) return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addTransportType(transport)
            .build()

        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                this@Link.network = network
                _state.value = _state.value.copy(
                    enabled = true,
                    connected = true,
                    note = null,
                )
            }

            override fun onCapabilitiesChanged(
                network: Network,
                caps: NetworkCapabilities,
            ) {
                this@Link.network = network
                _state.value = _state.value.copy(
                    enabled = true,
                    connected = true,
                    validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                    metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
                    downstreamKbps = caps.linkDownstreamBandwidthKbps,
                    note = null,
                )
            }

            override fun onLost(network: Network) {
                if (this@Link.network == network) this@Link.network = null
                _state.value = _state.value.copy(
                    connected = false,
                    validated = false,
                )
                // The request itself stays alive, so Android will call onAvailable again
                // if this transport comes back.
            }

            override fun onUnavailable() {
                _state.value = _state.value.copy(
                    enabled = true,
                    connected = false,
                    note = unavailableNote(),
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

    private fun scheduleRetry() {
        if (retryPending || !enabled) return
        retryPending = true
        handler.postDelayed({
            retryPending = false
            if (enabled && network == null) request()
        }, RETRY_DELAY_MS)
    }

    private fun unavailableNote(): String = when (transport) {
        NetworkCapabilities.TRANSPORT_CELLULAR ->
            "No mobile data. Check mobile data is on, and enable Developer options › Mobile data always active."
        NetworkCapabilities.TRANSPORT_WIFI -> "Not connected to a Wi-Fi network."
        NetworkCapabilities.TRANSPORT_ETHERNET -> "No USB/OTG Ethernet adapter detected."
        else -> "Network not available."
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
        const val RETRY_DELAY_MS = 20_000L
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
            transport = NetworkCapabilities.TRANSPORT_WIFI,
            color = 0xFF34D399,
            cm = cm,
            initiallyEnabled = prefs.getBoolean("wifi", true),
        ),
        Link(
            id = "cellular",
            title = "Mobile data",
            hint = "Carrier connection",
            transport = NetworkCapabilities.TRANSPORT_CELLULAR,
            color = 0xFF818CF8,
            cm = cm,
            initiallyEnabled = prefs.getBoolean("cellular", false),
        ),
        Link(
            id = "ethernet",
            title = "USB Ethernet",
            hint = "OTG adapter",
            transport = NetworkCapabilities.TRANSPORT_ETHERNET,
            color = 0xFFF59E0B,
            cm = cm,
            initiallyEnabled = prefs.getBoolean("ethernet", true),
        ),
    )

    fun link(id: String): Link? = links.firstOrNull { it.id == id }

    fun setEnabled(id: String, on: Boolean) {
        link(id)?.setEnabled(on)
        prefs.edit().putBoolean(id, on).apply()
    }

    fun usable(): List<Link> = links.filter { it.usable }

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
