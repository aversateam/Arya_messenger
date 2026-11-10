package dev.arya.messenger.network.relay

import dev.arya.messenger.data.AuthenticatedPeer
import dev.arya.messenger.data.IdentityEntity
import dev.arya.messenger.data.PeerSessionProtocol
import dev.arya.messenger.network.DeliveryOutcome
import dev.arya.messenger.network.FailureReason
import dev.arya.messenger.network.PeerRef
import dev.arya.messenger.network.PeerTransport
import dev.arya.messenger.network.RouteKind
import dev.arya.messenger.network.classifyFailure
import dev.arya.messenger.network.decodeUtf8
import dev.arya.messenger.security.RendezvousToken
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Delivers one message through a relay node when a direct connection is impossible.
 *
 * This is the route that works behind carrier-grade NAT: both devices open outbound connections, so
 * neither needs a public address or a forwarded port. The relay never stores anything and never
 * learns the plaintext — the two peers run the same authenticated session through the spliced
 * socket. Because nothing is stored, an offline recipient is reported as unreachable instead of
 * being silently queued; store-and-forward is Phase 6.5.
 *
 * The node is addressed by a rotating window handle, not by a peer id, so it cannot link traffic to
 * an identity or follow a device across time.
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
    private val inboxThreads = CopyOnWriteArrayList<Thread>()

    override fun start(displayName: String) {
        if (!running.compareAndSet(false, true)) return
        _available.value = true
        repeat(INBOX_SLOTS) { slot -> startInboxSlot(slot) }
    }

    override fun stop() {
        running.set(false)
        _available.value = false
        inboxThreads.forEach { runCatching { it.interrupt() } }
        inboxThreads.clear()
    }

    override suspend fun send(target: PeerRef, payload: ByteArray): DeliveryOutcome {
        // The relay pairs on a handle derived from the recipient's identity, so a target known only
        // by address cannot use this route.
        if (target.publicKey.isBlank()) return DeliveryOutcome.Failed(kind, FailureReason.UNREACHABLE)

        val identity = localIdentity() ?: return DeliveryOutcome.Failed(kind, FailureReason.TRANSPORT_UNAVAILABLE)
        val text = decodeUtf8(payload) ?: return DeliveryOutcome.Failed(kind, FailureReason.PAYLOAD_INVALID)
        val endpoints = relayEndpoints()
        if (endpoints.isEmpty()) return DeliveryOutcome.Failed(kind, FailureReason.TRANSPORT_UNAVAILABLE)

        var lastFailure = FailureReason.UNREACHABLE
        for (window in RendezvousToken.windowsFor(clock(), SEND_WINDOWS)) {
            val handle = runCatching { RendezvousToken.inboxHandle(target.publicKey, window) }.getOrNull()
                ?: return DeliveryOutcome.Failed(kind, FailureReason.PAYLOAD_INVALID)

            for (endpoint in endpoints) {
                val parsed = RelayProtocol.parseEndpoint(endpoint) ?: continue
                val result = withContext(Dispatchers.IO) { deliverOnce(parsed, handle, identity, text) }
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
        }
        return DeliveryOutcome.Failed(kind, lastFailure)
    }

    private suspend fun deliverOnce(
        endpoint: RelayProtocol.HostPort,
        handle: ByteArray,
        identity: IdentityEntity,
        text: String
    ): Result<AuthenticatedPeer> = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(endpoint.host, endpoint.port), CONNECT_TIMEOUT_MILLIS)
            socket.soTimeout = STATUS_TIMEOUT_MILLIS
            RelayProtocol.writeHello(
                DataOutputStream(socket.getOutputStream()),
                RelayProtocol.Role.DELIVER,
                handle
            )
            when (RelayProtocol.readStatus(DataInputStream(socket.getInputStream()))) {
                RelayProtocol.Status.PAIRED_INITIATOR -> {
                    socket.soTimeout = PeerSessionProtocol.TIMEOUT_MILLIS
                    protocol.initiate(socket.getInputStream(), socket.getOutputStream(), identity, text)
                }
                RelayProtocol.Status.PEER_OFFLINE ->
                    throw IllegalStateException("recipient is not attached to this relay")
                else -> throw IllegalStateException("relay refused the pairing")
            }
        }
    }

    /**
     * Keeps this device attached to a relay while the transport runs, so peers can reach it without
     * it having a reachable address. It dies with the process: Android background limits are handled
     * by the foreground service in Phase 6.5.
     */
    private fun startInboxSlot(slot: Int) {
        val thread = Thread {
            while (running.get()) {
                val served = runCatching { inboxOnce(slot) }.getOrDefault(false)
                if (!served && running.get()) runCatching { Thread.sleep(RECONNECT_DELAY_MILLIS) }
            }
        }
        thread.name = "arya-relay-inbox-$slot"
        thread.isDaemon = true
        inboxThreads += thread
        thread.start()
    }

    /** Returns true when a paired session actually ran, so the loop reconnects immediately. */
    private fun inboxOnce(slot: Int): Boolean {
        val identity = runBlocking { localIdentity() } ?: return false
        val endpoints = runBlocking { relayEndpoints() }
        if (endpoints.isEmpty()) return false

        val window = RendezvousToken.windowIndex(clock()) + slot
        val handle = RendezvousToken.inboxHandle(identity.publicKey, window)

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
                    handle
                )
                if (RelayProtocol.readStatus(DataInputStream(socket.getInputStream())) ==
                    RelayProtocol.Status.PAIRED_RESPONDER
                ) {
                    socket.soTimeout = PeerSessionProtocol.TIMEOUT_MILLIS
                    runBlocking { runCatching { protocol.respond(socket.getInputStream(), socket.getOutputStream()) } }
                    return true
                }
            } catch (_: Exception) {
                // Try the next endpoint; a failed inbox attachment is not user-visible state.
            } finally {
                runCatching { socket.close() }
            }
        }
        return false
    }

    private companion object {
        const val INBOX_SLOTS = 2
        const val SEND_WINDOWS = 2
        const val CONNECT_TIMEOUT_MILLIS = 5_000
        const val STATUS_TIMEOUT_MILLIS = 12_000
        const val RECONNECT_DELAY_MILLIS = 5_000L
    }
}
