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
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Direct internet TCP: the only route that needs no third party at all.
 *
 * This transport dials out. Inbound direct sessions are accepted by
 * [dev.arya.messenger.network.PeerEntryPort], which shares one port with the relay role, so there is
 * no separate listener here.
 *
 * The route works when the peer is genuinely reachable (public address, forwarded port, self-hosted
 * node, or a shared VPN). It cannot reach a peer behind carrier-grade NAT; that is what the relay
 * route is for.
 */
class DirectTcpTransport(
    private val protocol: PeerSessionProtocol,
    private val localIdentity: suspend () -> IdentityEntity?,
    private val clock: () -> Long = System::currentTimeMillis
) : PeerTransport, AutoCloseable {

    override val kind: RouteKind = RouteKind.DIRECT_TCP

    private val _available = MutableStateFlow(false)
    override val available: StateFlow<Boolean> = _available.asStateFlow()

    private val running = AtomicBoolean(false)

    override fun start(displayName: String) {
        _available.value = running.compareAndSet(false, true)
    }

    override fun stop() {
        running.set(false)
        _available.value = false
    }

    override fun close() = stop()

    override suspend fun send(target: PeerRef, payload: ByteArray): DeliveryOutcome {
        val addresses = target.addresses.filterIsInstance<PeerAddress.Tcp>()
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
                socket.soTimeout = PeerSessionProtocol.TIMEOUT_MILLIS
                protocol.initiate(socket.getInputStream(), socket.getOutputStream(), identity, text)
            }
        }
    }

    companion object {
        const val DEFAULT_PORT = 45871
        private const val CONNECT_TIMEOUT_MILLIS = 5_000
    }
}
