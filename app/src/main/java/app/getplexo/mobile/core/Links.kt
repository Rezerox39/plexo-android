package app.getplexo.mobile.core

import android.bluetooth.BluetoothManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.ConnectionPool
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Dispatcher
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import java.util.concurrent.CopyOnWriteArrayList
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

/** Live state of one physical link (radio). */
data class LinkState(
    val enabled: Boolean = false,
    val displayName: String = "",
    val color: Long = 0xFF4EA89A,
    val connected: Boolean = false,
    val validated: Boolean = false,
    val metered: Boolean = false,
    val downstreamKbps: Int = 0,
    val note: String? = null,
    /** True when the switch is on, but the OS radio behind it is known to be off. */
    val radioOff: Boolean = false,
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
    /** Shown while the link is on but the user has switched the radio off. */
    private val offNote: String,
    /** Shown while the link is on and the radio is on, but no network exists yet. */
    private val waitingNote: String,
    /** Reads the system radio switch; null when the platform hides it. */
    private val radioOn: () -> Boolean?,
) {
    private val _state = MutableStateFlow(
        LinkState(enabled = initiallyEnabled, displayName = title, color = color),
    )
    val state: StateFlow<LinkState> = _state.asStateFlow()

    fun setDisplayName(value: String) {
        _state.value = _state.value.copy(displayName = value.trim().take(40).ifBlank { title })
    }

    fun setColor(value: Long) {
        _state.value = _state.value.copy(color = value)
    }

    private var callback: ConnectivityManager.NetworkCallback? = null
    private var enabled = initiallyEnabled

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
        if (on) {
            _state.value = _state.value.copy(enabled = true)
            request()
        } else {
            _state.value = _state.value.copy(
                enabled = false,
                connected = false,
                validated = false,
                networkKey = null,
            )
        }
    }

    /**
     * Asks the platform for a network of this transport and keeps the request alive.
     *
     * Deliberately no timeout: the timed variant is released when it expires, so a
     * radio the user switches on afterwards is only picked up by the next retry (the
     * old code backed off to five minutes, which made the switch look dead). Without
     * a timeout Android keeps the request, brings the radio up when it can (mobile
     * data while Wi-Fi is on) and calls onAvailable again whenever a matching network
     * appears, including after the radio was turned off and on.
     *
     * No permission can make an app flip Wi-Fi or mobile data itself: Android 10+
     * blocks that for third party apps, so the panel offers a system shortcut instead.
     */
    private fun request() {
        val builder = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        transports.distinct().forEach { builder.addTransportType(it) }
        val request = builder.build()

        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                this@Link.network = network
                _state.value = _state.value.copy(
                    enabled = true,
                    connected = true,
                    note = null,
                    radioOff = false,
                    networkKey = keyFor(network),
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
                    radioOff = false,
                    networkKey = keyFor(network) ?: _state.value.networkKey,
                )
            }

            override fun onLost(network: Network) {
                // Another network of the same transport may still be live; only react
                // when the one we are using disappears.
                if (this@Link.network != network) return
                this@Link.network = null
                _state.value = _state.value.copy(
                    connected = false,
                    validated = false,
                    downstreamKbps = 0,
                    networkKey = null,
                )
                refreshNote()
            }
        }

        callback = cb
        try {
            cm.requestNetwork(request, cb)
        } catch (e: Exception) {
            // Not a normal permission error: Android rejects requests when an app has
            // too many outstanding callbacks. Either way, say so instead of staying silent.
            _state.value = _state.value.copy(
                enabled = true,
                note = if (e is SecurityException) "Permission denied: ${e.message}" else "Link unavailable: ${e.message}",
            )
            return
        }
        refreshNote()
    }

    /** Explains right away why an enabled link is not carrying traffic yet. */
    private fun refreshNote() {
        if (!enabled) return
        val radio = runCatching { radioOn() }.getOrNull()
        _state.value = _state.value.copy(
            radioOff = radio == false && network == null,
            note = when {
                network != null -> null
                radio == false -> offNote
                else -> waitingNote
            },
        )
    }

    private fun keyFor(network: Network): String? =
        runCatching { cm.getLinkProperties(network)?.interfaceName }.getOrNull()
            ?: network.toString()

    fun release() {
        enabled = false
        val cb = callback
        callback = null
        network = null
        cb?.let { runCatching { cm.unregisterNetworkCallback(it) } }
    }
}

/** Owns the radios and remembers which ones the user switched on. */
class LinkRegistry(context: Context) {

    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("plexo_links", Context.MODE_PRIVATE)
    private val cm = app.getSystemService(ConnectivityManager::class.java)

    private val wifiManager = app.getSystemService(WifiManager::class.java)
    private val bluetoothManager = app.getSystemService(BluetoothManager::class.java)

    // Reading the system switch is allowed; only flipping it is blocked for apps.
    @Suppress("DEPRECATION")
    private fun wifiRadioOn(): Boolean? = runCatching { wifiManager?.isWifiEnabled }.getOrNull()

    // From Android 12 this needs BLUETOOTH_CONNECT, which Plexo does not ask for, so the
    // answer is null there and the link shows the generic waiting note instead.
    @Suppress("DEPRECATION")
    private fun bluetoothRadioOn(): Boolean? =
        runCatching { bluetoothManager?.adapter?.isEnabled }.getOrNull()

    val links: List<Link> = listOf(
        Link(
            id = "wifi",
            title = "Wi-Fi",
            hint = "Your wireless router",
            transports = intArrayOf(NetworkCapabilities.TRANSPORT_WIFI),
            color = 0xFF4EA89A,
            cm = cm,
            initiallyEnabled = prefs.getBoolean("wifi", true),
            offNote = "Wi-Fi is switched off. Android does not let an app switch it on — tap “Turn on Wi-Fi”.",
            waitingNote = "No Wi-Fi network yet.",
            radioOn = { wifiRadioOn() },
        ),
        Link(
            id = "cellular",
            title = "Mobile data",
            hint = "Carrier connection",
            transports = intArrayOf(NetworkCapabilities.TRANSPORT_CELLULAR),
            color = 0xFF9C8FD6,
            cm = cm,
            initiallyEnabled = prefs.getBoolean("cellular", false),
            offNote = "Mobile data is not available. Android requires you to turn it on in the system panel.",
            waitingNote = "Waiting for mobile data. If it is switched off, turn it on with the button below; if it stays down while Wi-Fi is on, enable Developer options › Mobile data always active.",
            radioOn = { null },
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
            color = 0xFFD8A44C,
            cm = cm,
            initiallyEnabled = prefs.getBoolean("ethernet", true),
            offNote = "No wired adapter. Plug in a USB/OTG Ethernet adapter or a USB-C dock.",
            waitingNote = "No wired adapter. Plug in a USB/OTG Ethernet adapter or a USB-C dock.",
            radioOn = { null },
        ),
        Link(
            id = "bluetooth",
            title = "Bluetooth",
            hint = "PAN from a paired phone",
            transports = intArrayOf(NetworkCapabilities.TRANSPORT_BLUETOOTH),
            color = 0xFF6DB8C9,
            cm = cm,
            initiallyEnabled = prefs.getBoolean("bluetooth", false),
            offNote = "No Bluetooth PAN. Pair a phone that shares its connection, then turn on internet access for it in Bluetooth settings.",
            waitingNote = "No Bluetooth PAN. Pair a phone that shares its connection, then turn on internet access for it in Bluetooth settings.",
            radioOn = { bluetoothRadioOn() },
        ),
    )

    init {
        links.forEach { link ->
            prefs.getString("name_${link.id}", null)?.let { link.setDisplayName(it) }
            val color = prefs.getLong("color_${link.id}", link.color)
            link.setColor(color)
        }
    }

    fun link(id: String): Link? = links.firstOrNull { it.id == id }

    fun setEnabled(id: String, on: Boolean) {
        link(id)?.setEnabled(on)
        prefs.edit().putBoolean(id, on).apply()
    }

    fun rename(id: String, name: String) {
        val link = link(id) ?: return
        val clean = name.trim().take(40).ifBlank { link.title }
        link.setDisplayName(clean)
        prefs.edit().putString("name_$id", clean).apply()
    }

    fun setColor(id: String, color: Long) {
        link(id)?.setColor(color)
        prefs.edit().putLong("color_$id", color).apply()
    }

    /**
     * Links that can carry traffic right now.
     *
     * Two links can be handed different [Network] handles that sit on the same kernel
     * interface (a USB dock can look like both Ethernet and USB). Downloading over both
     * would not add bandwidth, so the later link is left out and the UI says why.
     */
    fun usable(allowed: Set<String>? = null): List<Link> {
        val seen = HashSet<String>()
        return links.filter { link ->
            val key = link.networkKey
            link.usable && key != null &&
                (allowed == null || link.id in allowed) &&
                seen.add(key)
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

/** Session cookies for one network client, enough for hosts that require a handshake. */
private class MemoryCookieJar : CookieJar {
    private val byHost = java.util.concurrent.ConcurrentHashMap<String, CopyOnWriteArrayList<Cookie>>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val list = byHost.getOrPut(url.host) { CopyOnWriteArrayList() }
        cookies.forEach { fresh ->
            list.removeAll { it.name == fresh.name && it.path == fresh.path }
            list.add(fresh)
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> =
        byHost[url.host]?.filter { it.matches(url) }.orEmpty()
}

/** DNS lookups through the same network, so split-DNS and captive links resolve correctly. */
private class NetworkDns(private val network: Network) : Dns {
    override fun lookup(hostname: String): List<InetAddress> =
        network.getAllByName(hostname).toList()
}

fun clientFor(network: Network): OkHttpClient = OkHttpClient.Builder()
    .socketFactory(BoundSocketFactory(network))
    .dns(NetworkDns(network))
    // One TCP connection per stream. With HTTP/2, OkHttp multiplexes every range
    // over a single connection, so the server's per-connection cap applies to the
    // whole download. Forcing HTTP/1.1 gives each worker its own socket, which is
    // the entire reason range downloads are fast.
    .protocols(listOf(Protocol.HTTP_1_1))
    // File hosts hand out a session cookie on the first response. Without a jar the
    // chunk requests lose it and the server answers 403/200 after a few seconds.
    .cookieJar(MemoryCookieJar())
    // Each client belongs to exactly one network, so its pool can never hand a Wi-Fi
    // socket to the cellular link. A deep pool keeps every chunk on a warm connection;
    // a fresh TCP+TLS handshake per chunk would waste a full round trip each time.
    .connectionPool(ConnectionPool(32, 5, TimeUnit.MINUTES))
    .dispatcher(
        Dispatcher().apply {
            maxRequests = 64
            maxRequestsPerHost = 64
        },
    )
    .addInterceptor { chain ->
        // identity encoding is important: a gzipped range no longer matches the file
        // offsets we write to. A browser-like header set also keeps CDNs and bot
        // filters from answering a plain download client with 403/429; the Referer
        // is the file's own origin, which is what a browser would send.
        val request = chain.request()
        val origin = request.url.newBuilder()
            .encodedPath("/")
            .query(null)
            .fragment(null)
            .build()
            .toString()
        chain.proceed(
            request.newBuilder()
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36",
                )
                .header("Accept", "*/*")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Referer", origin)
                .header("Accept-Encoding", "identity")
                .build(),
        )
    }
    .connectTimeout(20, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .writeTimeout(30, TimeUnit.SECONDS)
    .retryOnConnectionFailure(true)
    .build()
