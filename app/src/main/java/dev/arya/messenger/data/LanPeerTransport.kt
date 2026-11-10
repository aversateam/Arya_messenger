package dev.arya.messenger.data

import dev.arya.messenger.security.IdentityKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Listener and dialer for one-to-one authenticated sessions over TCP.
 *
 * Nothing here is LAN-specific any more: the same class serves a LAN socket and a reachable
 * internet endpoint. The wire protocol lives in [PeerSessionProtocol].
 */
class LanPeerTransport(
    localIdentity: suspend () -> IdentityEntity?,
    isTrustedPeer: suspend (publicKey: String) -> Boolean,
    onMessage: suspend (peerPublicKey: String, peerName: String, body: String) -> Unit,
    private val listenPort: Int = PORT,
    signIdentity: (alias: String, message: ByteArray) -> ByteArray = IdentityKeys::sign,
    private val maxConnections: Int = MAX_CONNECTIONS
) : AutoCloseable {

    companion object {
        const val PORT = 45871
        const val CONNECT_TIMEOUT_MILLIS = 5_000
        private const val MAX_CONNECTIONS = 16
    }

    val sessionProtocol = PeerSessionProtocol(localIdentity, isTrustedPeer, onMessage, signIdentity)

    private val running = AtomicBoolean(false)
    private val activeConnections = AtomicInteger(0)

    @Volatile
    private var server: ServerSocket? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        Thread {
            try {
                ServerSocket(listenPort).use { listener ->
                    server = listener
                    while (running.get()) {
                        val client = try { listener.accept() } catch (_: SocketException) { break }
                        Thread { handle(client) }.apply { name = "arya-secure-peer"; isDaemon = true }.start()
                    }
                }
            } catch (_: Exception) {
                running.set(false)
            }
        }.apply { name = "arya-secure-listener"; isDaemon = true }.start()
    }

    private fun handle(socket: Socket) {
        if (activeConnections.incrementAndGet() > maxConnections) {
            activeConnections.decrementAndGet()
            runCatching { socket.close() }
            return
        }
        try {
            socket.use { peer ->
                // Fail closed: no plaintext fallback and no unauthenticated delivery.
                runBlocking { runCatching { sessionProtocol.respond(peer) } }
            }
        } finally {
            activeConnections.decrementAndGet()
        }
    }

    suspend fun send(
        host: String,
        displayName: String,
        publicKey: String,
        keyAlias: String,
        body: String,
        port: Int = PORT
    ): Result<AuthenticatedPeer> = withContext(Dispatchers.IO) {
        runCatching {
            val identity = IdentityEntity(displayName = displayName, publicKey = publicKey, keyAlias = keyAlias)
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host.trim(), port), CONNECT_TIMEOUT_MILLIS)
                sessionProtocol.initiate(socket, identity, body)
            }
        }
    }

    /** Runs the session over a socket someone else opened and owns (relay-spliced connections). */
    suspend fun sendOver(
        socket: Socket,
        displayName: String,
        publicKey: String,
        keyAlias: String,
        body: String
    ): Result<AuthenticatedPeer> = withContext(Dispatchers.IO) {
        runCatching {
            sessionProtocol.initiate(
                socket,
                IdentityEntity(displayName = displayName, publicKey = publicKey, keyAlias = keyAlias),
                body
            )
        }
    }

    fun localIpv4Addresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>().filter { !it.isLoopbackAddress && it.isSiteLocalAddress }
            .map { it.hostAddress ?: "" }.filter { it.isNotBlank() }.distinct()
    }.getOrDefault(emptyList())

    override fun close() {
        running.set(false)
        runCatching { server?.close() }
        server = null
    }
}
