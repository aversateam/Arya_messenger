package dev.arya.messenger

import dev.arya.messenger.data.IdentityEntity
import dev.arya.messenger.data.PeerSessionProtocol
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Verifies the session protocol over a real socket pair.
 *
 * This replaces the former LAN transport test: the transport owner is gone, but the security
 * guarantees it carried (signed handshake, encrypted record layer, fail-closed untrusted peer) are
 * still the ones every internet route depends on.
 */
class PeerSessionProtocolTest {

    private fun identity(name: String): Pair<IdentityEntity, KeyPair> {
        val pair = KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp256r1")); generateKeyPair()
        }
        val encoded = Base64.getEncoder().encodeToString(pair.public.encoded)
        return IdentityEntity(displayName = name, publicKey = encoded, keyAlias = name) to pair
    }

    private fun signer(pair: KeyPair): (String, ByteArray) -> ByteArray = { _, transcript ->
        Signature.getInstance("SHA256withECDSA").run {
            initSign(pair.private); update(transcript); sign()
        }
    }

    private suspend fun <T> withResponder(protocol: PeerSessionProtocol, block: suspend (Int) -> T): T {
        val listener = ServerSocket(0)
        val port = listener.localPort
        val thread = Thread {
            runBlocking {
                runCatching {
                    listener.accept().use { socket ->
                        socket.soTimeout = PeerSessionProtocol.TIMEOUT_MILLIS
                        protocol.respond(socket.getInputStream(), socket.getOutputStream())
                    }
                }
            }
        }.apply { isDaemon = true; start() }
        return try {
            block(port)
        } finally {
            thread.join(2_000)
            runCatching { listener.close() }
        }
    }

    @Test
    fun trustedPeersCompleteSignedEncryptedHandshakeBeforeDelivery() = runBlocking {
        val (alice, alicePair) = identity("Alice")
        val (bob, bobPair) = identity("Bob")
        val received = CountDownLatch(1)
        val payload = AtomicReference<String>()

        val responder = PeerSessionProtocol(
            localIdentity = { bob },
            isTrustedPeer = { it == alice.publicKey },
            onMessage = { key, name, body ->
                assertEquals(alice.publicKey, key)
                payload.set("$name:$body")
                received.countDown()
            },
            signIdentity = signer(bobPair)
        )
        val initiator = PeerSessionProtocol(
            localIdentity = { alice },
            isTrustedPeer = { it == bob.publicKey },
            onMessage = { _, _, _ -> error("Unexpected inbound message") },
            signIdentity = signer(alicePair)
        )

        val authenticated = withResponder(responder) { port ->
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", port), 5_000)
                socket.soTimeout = PeerSessionProtocol.TIMEOUT_MILLIS
                initiator.initiate(socket.getInputStream(), socket.getOutputStream(), alice, "hello encrypted")
            }
        }

        assertTrue(received.await(2, TimeUnit.SECONDS))
        assertEquals("Alice:hello encrypted", payload.get())
        assertEquals(bob.publicKey, authenticated.publicKey)
        assertEquals("Bob", authenticated.displayName)
    }

    @Test
    fun untrustedPeerIsRejectedBeforeMessageDelivery() = runBlocking {
        val (alice, alicePair) = identity("Alice")
        val (bob, bobPair) = identity("Bob")
        val received = CountDownLatch(1)

        val responder = PeerSessionProtocol(
            localIdentity = { bob },
            isTrustedPeer = { false },
            onMessage = { _, _, _ -> received.countDown() },
            signIdentity = signer(bobPair)
        )
        val initiator = PeerSessionProtocol(
            localIdentity = { alice },
            isTrustedPeer = { it == bob.publicKey },
            onMessage = { _, _, _ -> },
            signIdentity = signer(alicePair)
        )

        val outcome = withResponder(responder) { port ->
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", port), 5_000)
                socket.soTimeout = PeerSessionProtocol.TIMEOUT_MILLIS
                runCatching { initiator.initiate(socket.getInputStream(), socket.getOutputStream(), alice, "must not arrive") }
            }
        }

        assertTrue("Untrusted peer must not be accepted", outcome.isFailure)
        assertTrue("No delivery may happen for an untrusted peer", !received.await(300, TimeUnit.MILLISECONDS))
    }
}
