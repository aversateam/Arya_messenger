package dev.arya.messenger.network

import dev.arya.messenger.data.IdentityEntity
import dev.arya.messenger.data.LanPeerTransport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Exposes the existing Phase 4 LAN transport through [PeerTransport].
 *
 * The LAN wire protocol, port, handshake transcript, and frame layout are unchanged; only the
 * caller and the error reporting differ. The LAN framing carries UTF-8 text only, so a payload
 * that is not valid UTF-8 is rejected as [FailureReason.PAYLOAD_INVALID] rather than silently
 * mangled. Binary payloads require the framed internet transport.
 */
class LanTransportAdapter(
    private val lan: LanPeerTransport,
    private val localIdentity: suspend () -> IdentityEntity?,
    private val port: Int = LanPeerTransport.PORT,
    private val clock: () -> Long = System::currentTimeMillis
) : PeerTransport {

    override val kind: RouteKind = RouteKind.LAN_TCP

    private val _available = MutableStateFlow(false)
    override val available: StateFlow<Boolean> = _available.asStateFlow()

    override fun start(displayName: String) {
        lan.start()
        _available.value = true
    }

    override fun stop() {
        lan.close()
        _available.value = false
    }

    fun localIpv4Addresses(): List<String> = lan.localIpv4Addresses()

    override suspend fun send(target: PeerRef, payload: ByteArray): DeliveryOutcome {
        val address = target.addresses
            .filterIsInstance<PeerAddress.Tcp>()
            .firstOrNull { it.scope == PeerAddress.Tcp.Scope.LAN }
            ?: return DeliveryOutcome.Failed(kind, FailureReason.UNREACHABLE)
        val identity = localIdentity()
            ?: return DeliveryOutcome.Failed(kind, FailureReason.TRANSPORT_UNAVAILABLE)
        val text = decodeUtf8(payload)
            ?: return DeliveryOutcome.Failed(kind, FailureReason.PAYLOAD_INVALID)

        val result = lan.send(
            address.host,
            identity.displayName,
            identity.publicKey,
            identity.keyAlias,
            text,
            address.port.takeIf { it > 0 } ?: port
        )
        return result.fold(
            onSuccess = { peer ->
                DeliveryOutcome.Delivered(
                    route = kind,
                    atMillis = clock(),
                    authenticatedPeer = PeerRef(peer.publicKey, peer.displayName, target.addresses)
                )
            },
            onFailure = { DeliveryOutcome.Failed(kind, classifyFailure(it)) }
        )
    }
}
