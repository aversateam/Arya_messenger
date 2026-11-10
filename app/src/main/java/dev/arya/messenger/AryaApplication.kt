package dev.arya.messenger

import android.app.Application
import dev.arya.messenger.data.AppRepository
import dev.arya.messenger.data.IdentityEntity
import dev.arya.messenger.data.NetworkConfig
import dev.arya.messenger.data.PeerSessionProtocol
import dev.arya.messenger.network.DefaultNetworkRuntime
import dev.arya.messenger.network.DirectTcpTransport
import dev.arya.messenger.network.NetworkRuntime
import dev.arya.messenger.network.PeerEntryPort
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
 * Owns the single repository, the network runtime, the shared inbound port, and the optional relay
 * node.
 *
 * There is no local-network mode: the runtime only knows the direct internet route and the relay
 * route. Inbound connections for both arrive on one port and are separated by their magic.
 */
class AryaApplication : Application() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var relayNode: RelayNode? = null
    private var entryPort: PeerEntryPort? = null

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

        val identityProvider: suspend () -> IdentityEntity? = { repository.getLocalIdentity() }
        val trustLookup: suspend (String) -> Boolean = { repository.isVerifiedPeer(it) }

        val directProtocol = PeerSessionProtocol(
            localIdentity = identityProvider,
            isTrustedPeer = trustLookup,
            onMessage = { peerKey, peerName, body ->
                repository.recordIncoming(peerKey, peerName, body, RouteKind.DIRECT_TCP)
            }
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
                DirectTcpTransport(directProtocol, identityProvider),
                relay
            )
        )

        // One port for everything we accept: direct sessions and relay pairings.
        entryPort = PeerEntryPort(
            listenPort = config.directListenPort(DirectTcpTransport.DEFAULT_PORT),
            protocol = directProtocol,
            relay = { relayNode }
        )

        scope.launch {
            // The identity is created during onboarding, so the runtime starts when it appears.
            val identity = repository.observe().filterNotNull().first()
            network.start(identity.displayName)
            applyNetworkConfig()
        }
    }

    /** Applies relay-node settings after the user edits them; safe to call repeatedly. */
    fun applyNetworkConfig() {
        if (config.nodeModeEnabled) {
            if (relayNode == null) relayNode = RelayNode()
        } else {
            relayNode?.close()
            relayNode = null
        }
        entryPort?.start()
    }

    /** True when this device is currently accepting relay pairings, for the settings surface. */
    fun isRelayNodeRunning(): Boolean = relayNode != null
}
