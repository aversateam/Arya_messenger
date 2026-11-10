package dev.arya.messenger

import dev.arya.messenger.security.GroupCrypto
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class GroupCryptoTest {
    private fun identity(): Pair<String, java.security.PrivateKey> {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(java.security.spec.ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        return Base64.getEncoder().encodeToString(pair.public.encoded) to pair.private
    }

    private fun signer(privateKey: java.security.PrivateKey): (ByteArray) -> ByteArray = { bytes ->
        Signature.getInstance("SHA256withECDSA").run { initSign(privateKey); update(bytes); sign() }
    }

    @Test fun signedGroupMessageRoundTrips() {
        val (publicKey, privateKey) = identity()
        val key = GroupCrypto.newGroupKey()
        val envelope = GroupCrypto.encrypt("g-1", 4, "m-1", "سلام آریا 👋", key, publicKey, signer(privateKey))
        assertEquals("سلام آریا 👋", GroupCrypto.decrypt(envelope, "g-1", 4, "m-1", setOf(publicKey), key))
    }

    @Test fun rejectsWrongGroupEpochMessageAndUnlistedSender() {
        val (publicKey, privateKey) = identity()
        val key = GroupCrypto.newGroupKey()
        val envelope = GroupCrypto.encrypt("g-1", 2, "m-1", "secret", key, publicKey, signer(privateKey))
        assertThrows(IllegalArgumentException::class.java) { GroupCrypto.decrypt(envelope, "g-2", 2, "m-1", setOf(publicKey), key) }
        assertThrows(IllegalArgumentException::class.java) { GroupCrypto.decrypt(envelope, "g-1", 3, "m-1", setOf(publicKey), key) }
        assertThrows(IllegalArgumentException::class.java) { GroupCrypto.decrypt(envelope, "g-1", 2, "m-2", setOf(publicKey), key) }
        assertThrows(IllegalArgumentException::class.java) { GroupCrypto.decrypt(envelope, "g-1", 2, "m-1", emptySet(), key) }
    }

    @Test fun rejectsCiphertextTamperingAndWrongKey() {
        val (publicKey, privateKey) = identity()
        val key = GroupCrypto.newGroupKey()
        val envelope = GroupCrypto.encrypt("g-1", 1, "m-1", "secret", key, publicKey, signer(privateKey))
        val bad = envelope.clone().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertThrows(IllegalArgumentException::class.java) { GroupCrypto.decrypt(bad, "g-1", 1, "m-1", setOf(publicKey), key) }
        assertThrows(Exception::class.java) { GroupCrypto.decrypt(envelope, "g-1", 1, "m-1", setOf(publicKey), GroupCrypto.newGroupKey()) }
    }

    @Test fun rejectsMalformedAndTrailingData() {
        val (publicKey, privateKey) = identity()
        val key = GroupCrypto.newGroupKey()
        val envelope = GroupCrypto.encrypt("g-1", 1, "m-1", "secret", key, publicKey, signer(privateKey))
        assertThrows(IllegalArgumentException::class.java) { GroupCrypto.decode(envelope + byteArrayOf(1)) }
        assertThrows(IllegalArgumentException::class.java) { GroupCrypto.decode(byteArrayOf(1, 2, 3)) }
    }
}
