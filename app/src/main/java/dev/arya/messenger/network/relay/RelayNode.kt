package dev.arya.messenger.network.relay

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Optional relay node that any Arya user can run on their own device.
 *
 * This is what keeps the network free to operate: there is no company-run server. A node is a dumb
 * byte pipe that pairs two outbound connections by peer id and splices them together. It stores
 * nothing, so it cannot leak message history, and it cannot read message content because the peers
 * encrypt end to end through the splice.
 *
 * It is only useful if other devices can reach it — a node behind carrier-grade NAT helps nobody.
 * Node mode is therefore opt-in and documented as "for publicly reachable devices, or devices
 * sharing a VPN".
 */
class RelayNode(
    private val listenPort: Int = DEFAULT_PORT,
    private val maxConnections: Int = MAX_CONNECTIONS
) : AutoCloseable {

    companion object {
        const val DEFAULT_PORT = 45890
        private const val MAX_CONNECTIONS = 64
        private const val HELLO_TIMEOUT_MILLIS = 10_000
        private const val PAIR_WAIT_MILLIS = 15_000L
        private const val POLL_INTERVAL_MILLIS = 250L
    }

    private val running = AtomicBoolean(false)
    private val active = AtomicInteger(0)
    private val waiting = ConcurrentHashMap<String, Waiting>()

    @Volatile
    private var server: ServerSocket? = null

    private class Waiting(val socket: Socket) {
        val paired = CountDownLatch(1)
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        Thread {
            try {
                ServerSocket(listenPort).use { listener ->
                    server = listener
                    while (running.get()) {
                        val client = try { listener.accept() } catch (_: Exception) { break }
                        Thread { handle(client) }.apply { name = "arya-relay-node-peer"; isDaemon = true }.start()
                    }
                }
            } catch (_: Exception) {
                running.set(false)
            }
        }.apply { name = "arya-relay-node"; isDaemon = true }.start()
    }

    override fun close() {
        running.set(false)
        runCatching { server?.close() }
        server = null
        waiting.values.forEach { runCatching { it.socket.close() } }
        waiting.clear()
    }

    private fun handle(socket: Socket) {
        if (active.incrementAndGet() > maxConnections) {
            active.decrementAndGet()
            runCatching { socket.close() }
            return
        }

        var ownedBySplice = false
        try {
            socket.soTimeout = HELLO_TIMEOUT_MILLIS
            val input = DataInputStream(socket.getInputStream())
            val out = DataOutputStream(socket.getOutputStream())
            val hello = RelayProtocol.readHello(input)

            when (hello.role) {
                RelayProtocol.Role.DELIVER -> {
                    val inbox = awaitInbox(RelayProtocol.keyOf(hello.targetId))
                    if (inbox == null) {
                        RelayProtocol.writeStatus(out, RelayProtocol.Status.PEER_OFFLINE)
                        return
                    }
                    RelayProtocol.writeStatus(out, RelayProtocol.Status.PAIRED_INITIATOR)
                    inbox.paired.countDown()
                    ownedBySplice = true
                    splice(socket, inbox.socket)
                }

                RelayProtocol.Role.INBOX -> {
                    val key = RelayProtocol.keyOf(hello.selfId)
                    val entry = Waiting(socket)
                    waiting.put(key, entry)?.let { stale -> runCatching { stale.socket.close() } }
                    try {
                        if (!entry.paired.await(PAIR_WAIT_MILLIS, TimeUnit.MILLISECONDS)) {
                            RelayProtocol.writeStatus(out, RelayProtocol.Status.REJECTED)
                            return
                        }
                        RelayProtocol.writeStatus(out, RelayProtocol.Status.PAIRED_RESPONDER)
                        // The DELIVER side owns the splice; this socket is now its responsibility.
                        ownedBySplice = true
                    } finally {
                        waiting.remove(key, entry)
                    }
                }
            }
        } catch (_: Exception) {
            // A node never fails loudly: bad peers are dropped.
        } finally {
            if (!ownedBySplice) runCatching { socket.close() }
            active.decrementAndGet()
        }
    }

    /** Waits briefly for an inbox to attach, so a slightly late recipient still pairs. */
    private fun awaitInbox(key: String): Waiting? {
        val deadline = System.currentTimeMillis() + PAIR_WAIT_MILLIS
        while (true) {
            waiting.remove(key)?.let { return it }
            if (System.currentTimeMillis() >= deadline) return null
            runCatching { Thread.sleep(POLL_INTERVAL_MILLIS) }
        }
    }

    private fun splice(deliverer: Socket, inbox: Socket) {
        val upstream = Thread { pump(deliverer, inbox) }.apply {
            name = "arya-relay-pump-up"
            isDaemon = true
            start()
        }
        pump(inbox, deliverer)
        runCatching { upstream.join(2_000) }
        runCatching { deliverer.close() }
        runCatching { inbox.close() }
    }

    private fun pump(from: Socket, to: Socket) {
        runCatching {
            val input = from.getInputStream()
            val output = to.getOutputStream()
            val buffer = ByteArray(8 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
                output.flush()
            }
        }
    }
}
