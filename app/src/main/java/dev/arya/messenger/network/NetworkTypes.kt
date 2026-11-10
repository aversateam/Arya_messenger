package dev.arya.messenger.network

/**
 * How a message actually travelled. Never inferred from configuration or discovery.
 *
 * There is no local-network route: Arya has no LAN mode, no mDNS/NSD, and no subnet discovery.
 * Every message crosses the internet.
 */
enum class RouteKind { DIRECT_QUIC, DIRECT_TCP, RELAY }

/**
 * Where a peer might be reachable. Addresses are unauthenticated hints: they are supplied by a user
 * or by a contact's QR invitation, and none of them prove who is answering.
 */
sealed interface PeerAddress {

    /** A TCP endpoint on the internet, e.g. "203.0.113.5:45871". */
    data class Tcp(val host: String, val port: Int) : PeerAddress

    /** libp2p multiaddr. Validated by the internet transport adapter, never here. */
    data class Multi(val address: String) : PeerAddress

    /** User- or contact-supplied relay endpoint, e.g. "relay.example:45890". */
    data class RelayHint(val address: String) : PeerAddress
}

/**
 * A peer we intend to reach.
 *
 * [publicKey] is the Base64 SPKI identity of the root P-256 key. It is blank when the target is
 * known only by address. In that case the identity is established by the transport handshake and
 * reported back in [DeliveryOutcome.Delivered.authenticatedPeer] — never assumed from the address.
 */
data class PeerRef(
    val publicKey: String = "",
    val displayName: String = "",
    val addresses: List<PeerAddress> = emptyList()
)

/** Why a delivery attempt failed. Callers must not collapse these into one "network error". */
enum class FailureReason {
    UNREACHABLE,
    TIMEOUT,
    UNTRUSTED_PEER,
    AUTH_FAILED,
    TRANSPORT_UNAVAILABLE,
    PAYLOAD_INVALID
}

sealed interface DeliveryOutcome {
    /**
     * [authenticatedPeer] carries the key the handshake actually proved. It is the only
     * trustworthy identity for the message about to be stored.
     */
    data class Delivered(
        val route: RouteKind,
        val atMillis: Long,
        val authenticatedPeer: PeerRef
    ) : DeliveryOutcome

    /** Accepted for later delivery. Reserved for the store-and-forward layer (Phase 6.5). */
    data class Queued(val reason: String) : DeliveryOutcome

    data class Failed(val route: RouteKind?, val reason: FailureReason) : DeliveryOutcome
}

sealed interface NetworkState {
    data object Stopped : NetworkState
    data object Starting : NetworkState
    data class Running(val reachability: Reachability, val activeRoutes: Set<RouteKind>) : NetworkState
    data class Degraded(val reason: String) : NetworkState
}

/** Result of reachability assessment. Stays [UNKNOWN] until AutoNAT lands in Phase 6.3. */
enum class Reachability { UNKNOWN, PRIVATE, PUBLIC }
