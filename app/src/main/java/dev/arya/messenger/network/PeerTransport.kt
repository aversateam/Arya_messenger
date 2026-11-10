package dev.arya.messenger.network

import kotlinx.coroutines.flow.StateFlow

/**
 * One replaceable way of reaching a peer.
 *
 * Implementations must not know about Compose, Room, conversations, or message formatting.
 * [payload] is opaque bytes: what an implementation may or may not do with it is documented by
 * that implementation, and sealing payloads for end-to-end delivery is a separate layer.
 */
interface PeerTransport {
    val kind: RouteKind

    /** Whether this route is currently usable. Must reflect real state, not intent. */
    val available: StateFlow<Boolean>

    fun start(displayName: String)
    fun stop()

    suspend fun send(target: PeerRef, payload: ByteArray): DeliveryOutcome
}
