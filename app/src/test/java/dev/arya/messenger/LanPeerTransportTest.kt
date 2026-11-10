package dev.arya.messenger

import dev.arya.messenger.data.IdentityEntity
import dev.arya.messenger.data.LanPeerTransport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class LanPeerTransportTest {
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

    @Test fun trustedPeersCompleteSignedEncryptedHandshakeBeforeDelivery() = runBlocking {
        val (alice, alicePair) = identity("Alice")
        val (bob, bobPair) = identity("Bob")
        val received = CountDownLatch(1)
        val payload = AtomicReference<String>()
        val receiver = LanPeerTransport(
            localIdentity = { bob },
            isTrustedPeer = { it == alice.publicKey },
            onMessage = { key, name, body ->
                assertEquals(alice.publicKey, key)
                payload.set("$name:$body")
                received.countDown()
            },
            listenPort = 45872,
            signIdentity = signer(bobPair)
        )
        val sender = LanPeerTransport(
            localIdentity = { alice },
            isTrustedPeer = { it == bob.publicKey },
            onMessage = { _, _, _ -> error("Unexpected inbound message") },
            listenPort = 45873,
            signIdentity = signer(alicePair)
        )
        try {
            receiver.start()
            Thread.sleep(150)
            val result = sender.send("127.0.0.1", alice.displayName, alice.publicKey, alice.keyAlias, "hello encrypted", 45872)
            assertTrue("Authenticated encrypted send should succeed: ${result.exceptionOrNull()}", result.isSuccess)
            assertTrue(received.await(1, TimeUnit.SECONDS))
            assertEquals("Alice:hello encrypted", payload.get())
        } finally {
            sender.close(); receiver.close()
        }
    }

    @Test fun untrustedPeerIsRejectedBeforeMessageDelivery() = runBlocking {
        val (alice, alicePair) = identity("Alice")
        val (bob, bobPair) = identity("Bob")
        val received = CountDownLatch(1)
        val receiver = LanPeerTransport(
            localIdentity = { bob }, isTrustedPeer = { false },
            onMessage = { _, _, _ -> received.countDown() }, listenPort = 45874,
            signIdentity = signer(bobPair)
        )
        val sender = LanPeerTransport(
            localIdentity = { alice }, isTrustedPeer = { it == bob.publicKey },
            onMessage = { _, _, _ -> }, listenPort = 45875,
            signIdentity = signer(alicePair)
        )
        try {
            receiver.start(); Thread.sleep(150)
            val result = sender.send("127.0.0.1", alice.displayName, alice.publicKey, alice.keyAlias, "must not arrive", 45874)
            assertTrue(result.isFailure)
            assertTrue(!received.await(250, TimeUnit.MILLISECONDS))
        } finally { sender.close(); receiver.close() }
    }
}
