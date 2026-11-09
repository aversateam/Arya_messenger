package dev.arya.messenger.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean

/** Manual-IP, same-Wi-Fi TCP transport. This first transport is intentionally plaintext. */
class LanPeerTransport(
    private val onMessage: suspend (peerPublicKey: String, peerName: String, body: String) -> Unit
) : AutoCloseable {
    companion object { const val PORT = 45871; private const val MAGIC = 0x4D534831; private const val MAX_TEXT = 16_000 }
    private val running = AtomicBoolean(false)
    @Volatile private var server: ServerSocket? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        Thread {
            try {
                ServerSocket(PORT).use { socket ->
                    server = socket
                    while (running.get()) {
                        val client = try { socket.accept() } catch (e: SocketException) { break }
                        Thread { handle(client) }.start()
                    }
                }
            } catch (_: Exception) {
                running.set(false)
            }
        }.apply { name = "arya-lan-listener"; isDaemon = true }.start()
    }

    private fun handle(socket: Socket) {
        socket.use { client ->
            client.soTimeout = 12_000
            val input = DataInputStream(client.getInputStream())
            val output = DataOutputStream(client.getOutputStream())
            try {
                require(input.readInt() == MAGIC) { "پروتکل شبکه محلی آریا پشتیبانی نمی‌شود" }
                val peerName = input.readUTF().take(64)
                val publicKey = input.readUTF().take(2048)
                val body = input.readUTF().take(MAX_TEXT)
                require(body.isNotBlank())
                kotlinx.coroutines.runBlocking { onMessage(publicKey, peerName, body) }
                output.writeBoolean(true)
                output.flush()
            } catch (_: Exception) {
                runCatching { output.writeBoolean(false); output.flush() }
            }
        }
    }

    suspend fun send(host: String, displayName: String, publicKey: String, body: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            require(body.isNotBlank() && body.length <= MAX_TEXT) { "پیام باید بین ۱ تا ۱۶۰۰۰ نویسه باشد" }
            Socket().use { socket ->
                socket.connect(java.net.InetSocketAddress(host.trim(), PORT), 5_000)
                socket.soTimeout = 8_000
                val output = DataOutputStream(socket.getOutputStream())
                val input = DataInputStream(socket.getInputStream())
                output.writeInt(MAGIC)
                output.writeUTF(displayName.take(64))
                output.writeUTF(publicKey.take(2048))
                output.writeUTF(body)
                output.flush()
                check(input.readBoolean()) { "گیرنده پیام را نپذیرفت" }
            }
        }
    }

    override fun close() {
        running.set(false)
        runCatching { server?.close() }
        server = null
    }

    fun localIpv4Addresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>().filter { !it.isLoopbackAddress && it.isSiteLocalAddress }
            .map { it.hostAddress ?: "" }.filter { it.isNotBlank() }.distinct()
    }.getOrDefault(emptyList())
}
