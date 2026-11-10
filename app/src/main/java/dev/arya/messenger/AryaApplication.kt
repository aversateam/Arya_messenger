package dev.arya.messenger

import android.app.Application
import dev.arya.messenger.data.AppRepository
import dev.arya.messenger.data.AryaNsd
import dev.arya.messenger.data.LanPeerTransport
import dev.arya.messenger.data.NetworkConfig
import dev.arya.messenger.data.PeerSessionProtocol
import dev.arya.messenger.network.DefaultNetworkRuntime
import dev.arya.messenger.network.DirectTcpTransport
import dev.arya.messenger.network.LanTransportAdapter
import dev.arya.messenger.network.NetworkRuntime
import dev.arya.messenger.network.RouteKind
import dev.arya.messenger.network.relay.RelayNode
import dev.arya.messenger.network.relay.RelayPeerTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Owns the single repository, the network runtime, and the optional relay node.
 *
 * Transport lifetime used to follow the Activity, so a rotation or a trip to the background tore
 * the listening socket down and NSD advertising was tied to composition. Both now live here, which
 * is also the prerequisite for running the same runtime from a foreground service in Phase 6.5.
 */
class AryaApplication : Application() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var nsd: AryaNsd? = null
    private var relayNode: RelayNode? = null

    lateinit var repository: AppRepository
        private set

    lateinit var network: NetworkRuntime
        private set

    lateinit var config: NetworkConfig
        private set

    override fun onCreate() {
        super.onCreate()
        repository = AppRepository(this)
        config = NetworkConfig(this)

        val identityProvider: suspend () -> dev.arya.messenger.data.IdentityEntity? =
            { repository.getLocalIdentity() }
        val trustLookup: suspend (String) -> Boolean = { repository.isVerifiedPeer(it) }

        val lan = LanPeerTransport(
            localIdentity = identityProvider,
            isTrustedPeer = trustLookup,
            onMessage = { peerKey, peerName, body ->
                repository.recordIncoming(peerKey, peerName, body, RouteKind.LAN_TCP)
            }
        )

        val direct = DirectTcpTransport(
            protocol = PeerSessionProtocol(
                localIdentity = identityProvider,
                isTrustedPeer = trustLookup,
                // The direct route has no inbound listener; it only ever initiates.
                onMessage = { _, _, _ -> }
            ),
            localIdentity = identityProvider
        )

        val relay = RelayPeerTransport(
            protocol = PeerSessionProtocol(
                localIdentity = identityProvider,
                isTrustedPeer = trustLookup,
                onMessage = { peerKey, peerName, body ->
                    repository.recordIncoming(peerKey, peerName, body, RouteKind.RELAY)
                }
            ),
            localIdentity = identityProvider,
            relayEndpoints = { config.relayEndpoints }
        )

        network = DefaultNetworkRuntime(
            listOf(
                LanTransportAdapter(lan = lan, localIdentity = identityProvider),
                direct,
                relay
            )
        )

        scope.launch {
            val identity = repository.observe().filterNotNull().first()
            network.start(identity.displayName)
            nsd = AryaNsd(this@AryaApplication).also {
                it.register(identity.displayName, LanPeerTransport.PORT)
            }
            applyNetworkConfig()
        }
    }

    /** Applies relay-node settings after the user edits them; safe to call repeatedly. */
    fun applyNetworkConfig() {
        if (config.nodeModeEnabled) {
            if (relayNode == null) relayNode = RelayNode(config.nodePort).also { it.start() }
        } else {
            relayNode?.close()
            relayNode = null
        }
    }

    /** True when this device is currently accepting relay pairings, for the settings screen. */
    fun isRelayNodeRunning(): Boolean = relayNode != null
}
