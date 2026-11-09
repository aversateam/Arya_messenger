package dev.arya.messenger.data

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.util.concurrent.ConcurrentHashMap

/**
 * NSD-based discovery of Arya peers on the local network.
 * Peers advertise _arya._tcp. on the LAN TCP port; discovery resolves their IP.
 * Manual-IP connection is kept alongside this automatic path.
 */
class AryaNsd(private val context: Context) {

    companion object {
        const val SERVICE_TYPE = "_arya._tcp."
        private const val ATTR_VERSION = "v"

        @Volatile
        var registeredName: String? = null
    }

    private val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager

    @Volatile
    private var registrationListener: NsdManager.RegistrationListener? = null

    fun register(displayName: String, port: Int) {
        val name = "آریا • ${displayName.trim().take(48).ifBlank { "Arya" }}"
        val info = NsdServiceInfo().apply {
            serviceType = SERVICE_TYPE
            serviceName = name
            setPort(port)
            setAttribute(ATTR_VERSION, "1")
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(service: NsdServiceInfo) {
                registeredName = service.serviceName
            }
            override fun onRegistrationFailed(service: NsdServiceInfo, errorCode: Int) {}
            override fun onServiceUnregistered(service: NsdServiceInfo) {}
            override fun onUnregistrationFailed(service: NsdServiceInfo, errorCode: Int) {}
        }
        registrationListener = listener
        runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
    }

    fun unregister() {
        registrationListener?.let { runCatching { nsd.unregisterService(it) } }
        registrationListener = null
        registeredName = null
    }

    data class Peer(val name: String, val host: String, val port: Int)

    /**
     * Starts browsing for Arya services. Returns a stop function; call it when the
     * screen that requested discovery leaves composition.
     */
    fun discover(onPeers: (List<Peer>) -> Unit): () -> Unit {
        val peers = ConcurrentHashMap<String, Peer>()
        val emit = { onPeers(peers.values.toList().sortedBy { it.name }) }
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                if (info.serviceName == registeredName) return
                runCatching {
                    nsd.resolveService(info, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
                        override fun onServiceResolved(service: NsdServiceInfo) {
                            val host = service.host?.hostAddress ?: return
                            peers[service.serviceName] = Peer(service.serviceName, host, service.port)
                            emit()
                        }
                    })
                }
            }
            override fun onServiceLost(service: NsdServiceInfo) {
                peers.remove(service.serviceName)
                emit()
            }
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        runCatching { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
        return { runCatching { nsd.stopServiceDiscovery(listener) } }
    }
}
