package dk.airsync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest

/**
 * Binder hele AirTooth til WiFi.
 *
 * Samsung kan skifte telefonen over på mobildata, når WiFi er lidt ustabilt ("Intelligent Wi-Fi").
 * Så sendes trafikken til højttalerne ud over mobilnettet, hvor de ikke findes ("No route to host").
 * Ved at bede om WiFi-netværket og binde processen til det, går alt fra AirTooth altid over WiFi.
 */
object WifiBinder {
    private var callback: ConnectivityManager.NetworkCallback? = null

    fun start(context: Context) {
        if (callback != null) return
        val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            // Højttalerne er på det lokale net; WiFi uden internetadgang er også godt nok.
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (cm.bindProcessToNetwork(network)) AppState.log("Netværk: AirTooth bruger WiFi")
            }

            override fun onLost(network: Network) {
                if (cm.boundNetworkForProcess == network) {
                    cm.bindProcessToNetwork(null)
                    AppState.log("Netværk: WiFi er tabt")
                }
            }
        }
        try {
            cm.requestNetwork(request, cb)
            callback = cb
        } catch (e: Exception) {
            AppState.log("Netværk: kunne ikke låse til WiFi (${e.message})")
        }
    }
}
