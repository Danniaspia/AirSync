package dk.airsync

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.net.Inet4Address
import java.net.InetAddress

/** Finder AirPlay-højttalere (_raop._tcp) på WiFi. Opløsninger køres én ad gangen, som NsdManager kræver. */
class Discovery(context: Context, private val onChange: (List<Speaker>) -> Unit) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val main = Handler(Looper.getMainLooper())
    private val found = LinkedHashMap<String, Speaker>()
    private val queue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var listener: NsdManager.DiscoveryListener? = null

    fun start() {
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                AppState.log("Søger efter AirPlay-højttalere…")
            }

            override fun onDiscoveryStopped(serviceType: String) {}

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                AppState.log("Søgning fejlede (kode $errorCode)")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}

            override fun onServiceFound(info: NsdServiceInfo) {
                main.post {
                    queue.addLast(info)
                    next()
                }
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                main.post { if (found.remove(info.serviceName) != null) publish() }
            }
        }
        listener = l
        nsd.discoverServices("_raop._tcp", NsdManager.PROTOCOL_DNS_SD, l)
    }

    fun stop() {
        listener?.let {
            try {
                nsd.stopServiceDiscovery(it)
            } catch (_: Exception) {
            }
        }
        listener = null
    }

    /** Søger forfra, fx når WiFi lige er blevet tændt – en søgning startet uden WiFi finder ingenting. */
    fun restart() {
        stop()
        found.clear()
        queue.clear()
        resolving = false
        publish()
        start()
    }

    @Suppress("DEPRECATION")
    private fun next() {
        if (resolving) return
        val info = queue.removeFirstOrNull() ?: return
        resolving = true
        nsd.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                main.post {
                    resolving = false
                    next()
                }
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                main.post {
                    add(serviceInfo)
                    resolving = false
                    next()
                }
            }
        })
    }

    private fun add(info: NsdServiceInfo) {
        val host = ipv4Of(info) ?: return
        val txt = HashMap<String, String>()
        for ((k, v) in info.attributes) txt[k] = v?.let { String(it, Charsets.UTF_8) } ?: ""
        val raw = info.serviceName
        found[raw] = Speaker(raw, raw.substringAfter('@', raw), host, info.port, txt)
        publish()
    }

    @Suppress("DEPRECATION")
    private fun ipv4Of(info: NsdServiceInfo): String? {
        val addresses: List<InetAddress> =
            if (Build.VERSION.SDK_INT >= 34) info.hostAddresses else listOfNotNull(info.host)
        val addr = addresses.firstOrNull { it is Inet4Address } ?: addresses.firstOrNull()
        return addr?.hostAddress
    }

    private fun publish() {
        onChange(found.values.sortedBy { it.name.lowercase() })
    }
}
