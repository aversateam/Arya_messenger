package dev.arya.messenger.network

import dev.arya.messenger.data.IdentityEntity
import dev.arya.messenger.data.PeerSessionProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Direct internet TCP dial.
 *
 * This is the only internet route that needs no third party at all, so it costs nothing to
 * operate. It works when the peer is actually reachable: a public address, a forwarded port, a
 * self-hosted node, or a shared VPN. It cannot reach a peer behind carrier-grade NAT, which is why
 * [dev.arya.messenger.network.relay.RelayPeerTransport] exists.
 *
 * The session protocol is the same authenticated one used on the LAN.
 */
class DirectTcpTransport(
    private val protocol: PeerSessionProtocol,
    private val localIdentity: suspend () -> IdentityEntity?,
    private val clock: () -> Long = System::currentTimeMillis
) : PeerTransport {

    override val kind: RouteKind = RouteKind.DIRECT_TCP

    private val _available = MutableStateFlow(true)
    override val available: StateFlow<Boolean> = _available.asStateFlow()

    override fun start(displayName: String) {
        _available.value = true
    }

    override fun stop() {
        _available.value = false
    }

    override suspend fun send(target: PeerRef, payload: ByteArray): DeliveryOutcome {
        val addresses = target.addresses
            .filterIsInstance<PeerAddress.Tcp>()
            .filter { it.scope == PeerAddress.Tcp.Scope.INTERNET }
        if (addresses.isEmpty()) return DeliveryOutcome.Failed(kind, FailureReason.UNREACHABLE)

        val identity = localIdentity() ?: return DeliveryOutcome.Failed(kind, FailureReason.TRANSPORT_UNAVAILABLE)
        val text = decodeUtf8(payload) ?: return DeliveryOutcome.Failed(kind, FailureReason.PAYLOAD_INVALID)

        var lastFailure = FailureReason.UNREACHABLE
        for (address in addresses) {
            val outcome = dialAndDeliver(address, identity, text)
            val peer = outcome.getOrNull()
            if (peer != null) {
                return DeliveryOutcome.Delivered(
                    route = kind,
                    atMillis = clock(),
                    authenticatedPeer = PeerRef(peer.publicKey, peer.displayName, target.addresses)
                )
            }
            lastFailure = classifyFailure(outcome.exceptionOrNull())
        }
        return DeliveryOutcome.Failed(kind, lastFailure)
    }

    private suspend fun dialAndDeliver(
        address: PeerAddress.Tcp,
        identity: IdentityEntity,
        text: String
    ) = withContext(Dispatchers.IO) {
        runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(address.host.trim(), address.port), CONNECT_TIMEOUT_MILLIS)
                protocol.initiate(socket, identity, text)
            }
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 5_000
    }
}
