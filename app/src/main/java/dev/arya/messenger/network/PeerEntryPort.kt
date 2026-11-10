package dev.arya.messenger.network

import dev.arya.messenger.data.PeerSessionProtocol
import dev.arya.messenger.network.relay.RelayNode
import dev.arya.messenger.network.relay.RelayProtocol
import kotlinx.coroutines.runBlocking
import java.io.InputStream
import java.io.PushbackInputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * One TCP port for everything this device accepts.
 *
 * Both roles start with a four-byte magic, so the port can dispatch without a second listener:
 * `ARY4` is an authenticated session (a peer sending us a message), `ARYR` is a relay pairing.
 * Only the four peeked bytes are replayed; after the handshake or hello the raw socket stream is
 * used untouched, so no protocol code needs to know it was multiplexed.
 *
 * Anything with an unknown magic is dropped immediately.
 */
class PeerEntryPort(
    private val listenPort: Int,
    private val protocol: PeerSessionProtocol,
    private val relay: () -> RelayNode? = { null },
    private val maxConnections: Int = MAX_CONNECTIONS
) : AutoCloseable {

    private val running = AtomicBoolean(false)
    private val active = AtomicInteger(0)

    @Volatile
    private var server: ServerSocket? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        Thread {
            try {
                ServerSocket(listenPort).use { listener ->
                    server = listener
                    while (running.get()) {
                        val client = try { listener.accept() } catch (_: Exception) { break }
                        Thread { handle(client) }.apply {
                            name = "arya-entry-peer"
                            isDaemon = true
                        }.start()
                    }
                }
            } catch (_: Exception) {
                running.set(false)
            }
        }.apply { name = "arya-entry-listener"; isDaemon = true }.start()
    }

    override fun close() {
        running.set(false)
        runCatching { server?.close() }
        server = null
    }

    private fun handle(socket: Socket) {
        if (active.incrementAndGet() > maxConnections) {
            active.decrementAndGet()
            runCatching { socket.close() }
            return
        }

        var ownedByRelay = false
        try {
            socket.soTimeout = PEEK_TIMEOUT_MILLIS
            val pushback = PushbackInputStream(socket.getInputStream(), MAGIC_BYTES)
            val magic = ByteArray(MAGIC_BYTES)
            if (!readFully(pushback, magic)) return
            pushback.unread(magic)

            socket.soTimeout = PeerSessionProtocol.TIMEOUT_MILLIS
            val output = socket.getOutputStream()

            when (magicValue(magic)) {
                PeerSessionProtocol.MAGIC ->
                    runBlocking { runCatching { protocol.respond(pushback, output) } }

                RelayProtocol.MAGIC -> {
                    val node = relay() ?: return
                    ownedByRelay = true
                    node.handle(socket, pushback, output)
                }

                else -> Unit
            }
        } catch (_: Exception) {
            // Drop anything that does not speak an Arya protocol.
        } finally {
            if (!ownedByRelay) runCatching { socket.close() }
            active.decrementAndGet()
        }
    }

    private fun readFully(input: InputStream, buffer: ByteArray): Boolean {
        var read = 0
        while (read < buffer.size) {
            val count = input.read(buffer, read, buffer.size - read)
            if (count < 0) return false
            read += count
        }
        return true
    }

    private fun magicValue(bytes: ByteArray): Int =
        ((bytes[0].toInt() and 0xFF) shl 24) or
            ((bytes[1].toInt() and 0xFF) shl 16) or
            ((bytes[2].toInt() and 0xFF) shl 8) or
            (bytes[3].toInt() and 0xFF)

    private companion object {
        const val MAGIC_BYTES = 4
        const val PEEK_TIMEOUT_MILLIS = 10_000
        const val MAX_CONNECTIONS = 32
    }
}
