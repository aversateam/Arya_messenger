package dev.arya.messenger.network.relay

import dev.arya.messenger.data.IdentityEntity
import dev.arya.messenger.data.PeerSessionProtocol
import dev.arya.messenger.network.DeliveryOutcome
import dev.arya.messenger.network.FailureReason
import dev.arya.messenger.network.PeerRef
import dev.arya.messenger.network.PeerTransport
import dev.arya.messenger.network.RouteKind
import dev.arya.messenger.network.classifyFailure
import dev.arya.messenger.network.decodeUtf8
import dev.arya.messenger.security.PeerIds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Delivers one message through a relay node when a direct connection is impossible.
 *
 * This is the "works like a normal messenger" path: both devices open an outbound connection, so
 * neither needs a public address or a forwarded port. The relay never stores anything and never
 * learns the plaintext — the two peers run the same authenticated Phase 4 session through the
 * spliced socket. Because nothing is stored, an offline recipient is reported as unreachable
 * instead of being silently queued; store-and-forward arrives in Phase 6.5 and needs the sealed
 * envelope layer from 6.6.
 *
 * Any user can run a node: see [dev.arya.messenger.network.relay.RelayNode].
 */
class RelayPeerTransport(
    private val protocol: PeerSessionProtocol,
    private val localIdentity: suspend () -> IdentityEntity?,
    private val relayEndpoints: suspend () -> List<String>,
    private val clock: () -> Long = System::currentTimeMillis
) : PeerTransport {

    override val kind: RouteKind = RouteKind.RELAY

    private val _available = MutableStateFlow(false)
    override val available: StateFlow<Boolean> = _available.asStateFlow()

    private val running = AtomicBoolean(false)
    @Volatile
    private var inbox: Thread? = null

    override fun start(displayName: String) {
        if (!running.compareAndSet(false, true)) return
        _available.value = true
        startInbox()
    }

    override fun stop() {
        running.set(false)
        _available.value = false
        inbox?.interrupt()
        inbox = null
    }

    override suspend fun send(target: PeerRef, payload: ByteArray): DeliveryOutcome {
        // A relay pairs peers by identity, so an address-only target cannot use this route.
        if (target.publicKey.isBlank()) return DeliveryOutcome.Failed(kind, FailureReason.UNREACHABLE)

        val identity = localIdentity() ?: return DeliveryOutcome.Failed(kind, FailureReason.TRANSPORT_UNAVAILABLE)
        val text = decodeUtf8(payload) ?: return DeliveryOutcome.Failed(kind, FailureReason.PAYLOAD_INVALID)
        val targetId = runCatching { PeerIds.digestBytes(target.publicKey) }.getOrNull()
            ?: return DeliveryOutcome.Failed(kind, FailureReason.PAYLOAD_INVALID)

        val endpoints = relayEndpoints()
        if (endpoints.isEmpty()) return DeliveryOutcome.Failed(kind, FailureReason.TRANSPORT_UNAVAILABLE)

        var lastFailure = FailureReason.UNREACHABLE
        for (endpoint in endpoints) {
            val parsed = RelayProtocol.parseEndpoint(endpoint) ?: continue
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress(parsed.host, parsed.port), CONNECT_TIMEOUT_MILLIS)
                        socket.soTimeout = STATUS_TIMEOUT_MILLIS
                        RelayProtocol.writeHello(
                            DataOutputStream(socket.getOutputStream()),
                            RelayProtocol.Role.DELIVER,
                            PeerIds.digestBytes(identity.publicKey),
                            targetId
                        )
                        when (RelayProtocol.readStatus(DataInputStream(socket.getInputStream()))) {
                            RelayProtocol.Status.PAIRED_INITIATOR -> {
                                socket.soTimeout = PeerSessionProtocol.TIMEOUT_MILLIS
                                protocol.initiate(socket, identity, text)
                            }
                            RelayProtocol.Status.PEER_OFFLINE ->
                                throw IllegalStateException("recipient is not attached to this relay")
                            else -> throw IllegalStateException("relay refused the pairing")
                        }
                    }
                }
            }
            val peer = result.getOrNull()
            if (peer != null) {
                return DeliveryOutcome.Delivered(
                    route = kind,
                    atMillis = clock(),
                    authenticatedPeer = PeerRef(peer.publicKey, peer.displayName, target.addresses)
                )
            }
            lastFailure = classifyFailure(result.exceptionOrNull())
        }
        return DeliveryOutcome.Failed(kind, lastFailure)
    }

    /**
     * Keeps an inbox attached to a relay while the transport is running, so other peers can reach
     * this device without it having a reachable address. This dies with the process: Android
     * background execution limits are handled by the foreground service in Phase 6.5.
     */
    private fun startInbox() {
        val thread = Thread {
            while (running.get()) {
                val served = runCatching { inboxOnce() }.getOrDefault(false)
                if (!served && running.get()) runCatching { Thread.sleep(RECONNECT_DELAY_MILLIS) }
            }
        }
        thread.name = "arya-relay-inbox"
        thread.isDaemon = true
        inbox = thread
        thread.start()
    }

    /** Returns true when a paired session actually ran, so the loop reconnects immediately. */
    private fun inboxOnce(): Boolean {
        val identity = runBlocking { localIdentity() } ?: return false
        val endpoints = runBlocking { relayEndpoints() }
        if (endpoints.isEmpty()) return false
        val selfId = PeerIds.digestBytes(identity.publicKey)

        for (endpoint in endpoints) {
            if (!running.get()) return false
            val parsed = RelayProtocol.parseEndpoint(endpoint) ?: continue
            val socket = runCatching {
                Socket().apply {
                    connect(InetSocketAddress(parsed.host, parsed.port), CONNECT_TIMEOUT_MILLIS)
                    soTimeout = STATUS_TIMEOUT_MILLIS
                }
            }.getOrNull() ?: continue
            try {
                RelayProtocol.writeHello(
                    DataOutputStream(socket.getOutputStream()),
                    RelayProtocol.Role.INBOX,
                    selfId,
                    ByteArray(RelayProtocol.ID_BYTES)
                )
                if (RelayProtocol.readStatus(DataInputStream(socket.getInputStream())) ==
                    RelayProtocol.Status.PAIRED_RESPONDER
                ) {
                    socket.soTimeout = PeerSessionProtocol.TIMEOUT_MILLIS
                    runBlocking { runCatching { protocol.respond(socket) } }
                    return true
                }
            } catch (_: Exception) {
                // Fall through to the next endpoint; a failed inbox is not user-visible state.
            } finally {
                runCatching { socket.close() }
            }
        }
        return false
    }

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 5_000
        const val STATUS_TIMEOUT_MILLIS = 20_000
        const val RECONNECT_DELAY_MILLIS = 5_000L
    }
}
