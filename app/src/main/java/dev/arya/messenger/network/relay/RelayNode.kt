package dev.arya.messenger.network.relay

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Optional relay node that any Arya user can run on their own device.
 *
 * This is what keeps the network free to operate: there is no company-run server. A node is a dumb
 * byte pipe that pairs two outbound connections on a rotating window handle and splices them
 * together. It stores nothing, so it cannot leak message history, has no user database and requires
 * no account. It cannot read message content because the peers encrypt end to end through the
 * splice.
 *
 * It does not own a socket: [dev.arya.messenger.network.PeerEntryPort] accepts connections on the
 * single shared port and hands relay traffic here.
 */
class RelayNode(private val maxConnections: Int = MAX_CONNECTIONS) : AutoCloseable {

    private val active = AtomicInteger(0)
    private val waiting = ConcurrentHashMap<String, Waiting>()

    private class Waiting(val socket: Socket) {
        val paired = CountDownLatch(1)
    }

    /**
     * Handles one connection that has already been identified as relay traffic by its magic.
     * [input] may be a pushback stream still holding those four bytes; everything after the hello is
     * read from the raw socket, because the pushback buffer is empty by then.
     */
    fun handle(socket: Socket, input: InputStream, output: OutputStream) {
        if (active.incrementAndGet() > maxConnections) {
            active.decrementAndGet()
            runCatching { socket.close() }
            return
        }

        var ownedBySplice = false
        try {
            socket.soTimeout = HELLO_TIMEOUT_MILLIS
            val source = DataInputStream(input)
            val sink = DataOutputStream(output)
            val hello = RelayProtocol.readHello(source)
            val key = RelayProtocol.keyOf(hello.token)

            when (hello.role) {
                RelayProtocol.Role.DELIVER -> {
                    val inbox = awaitInbox(key)
                    if (inbox == null) {
                        RelayProtocol.writeStatus(sink, RelayProtocol.Status.PEER_OFFLINE)
                        return
                    }
                    RelayProtocol.writeStatus(sink, RelayProtocol.Status.PAIRED_INITIATOR)
                    inbox.paired.countDown()
                    ownedBySplice = true
                    splice(socket, inbox.socket)
                }

                RelayProtocol.Role.INBOX -> {
                    val entry = Waiting(socket)
                    waiting.put(key, entry)?.let { stale -> runCatching { stale.socket.close() } }
                    try {
                        if (!entry.paired.await(PAIR_WAIT_MILLIS, TimeUnit.MILLISECONDS)) {
                            RelayProtocol.writeStatus(sink, RelayProtocol.Status.REJECTED)
                            return
                        }
                        RelayProtocol.writeStatus(sink, RelayProtocol.Status.PAIRED_RESPONDER)
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

    override fun close() {
        waiting.values.forEach { runCatching { it.socket.close() } }
        waiting.clear()
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

    private companion object {
        const val MAX_CONNECTIONS = 64
        const val HELLO_TIMEOUT_MILLIS = 10_000
        const val PAIR_WAIT_MILLIS = 8_000L
        const val POLL_INTERVAL_MILLIS = 200L
    }
}
