package dev.arya.messenger

import dev.arya.messenger.security.EphemeralSessionCrypto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.ByteBuffer
import java.security.MessageDigest

class EphemeralSessionCryptoTest {
    @Test fun bothSidesDeriveMatchingOppositeDirectionalKeys() {
        val initiator = EphemeralSessionCrypto.newEphemeralKeyPair()
        val responder = EphemeralSessionCrypto.newEphemeralKeyPair()
        val transcript = MessageDigest.getInstance("SHA-256").digest(
            initiator.public.encoded + responder.public.encoded + "arya-v1".toByteArray()
        )
        val a = EphemeralSessionCrypto.deriveSessionKeys(initiator.private, responder.public.encoded, transcript, true)
        val b = EphemeralSessionCrypto.deriveSessionKeys(responder.private, initiator.public.encoded, transcript, false)
        assertArrayEquals(a.sendKey, b.receiveKey)
        assertArrayEquals(a.receiveKey, b.sendKey)
    }

    @Test fun transcriptChangeProducesDifferentKeys() {
        val a = EphemeralSessionCrypto.newEphemeralKeyPair()
        val b = EphemeralSessionCrypto.newEphemeralKeyPair()
        val first = EphemeralSessionCrypto.deriveSessionKeys(a.private, b.public.encoded, "transcript-a".toByteArray(), true)
        val second = EphemeralSessionCrypto.deriveSessionKeys(a.private, b.public.encoded, "transcript-b".toByteArray(), true)
        org.junit.Assert.assertFalse(first.sendKey.contentEquals(second.sendKey))
    }

    @Test fun recordsEncryptDecryptAndRejectTampering() {
        val keyA = ByteArray(32) { it.toByte() }
        val keyB = ByteArray(32) { (it + 1).toByte() }
        val sender = EphemeralSessionCrypto.RecordCipher(keyA, keyB)
        val receiver = EphemeralSessionCrypto.RecordCipher(keyB, keyA)
        val plaintext = "پیام محرمانه آریا".toByteArray()
        val record = sender.encrypt(plaintext)
        assertArrayEquals(plaintext, receiver.decrypt(record))

        val next = sender.encrypt("second".toByteArray())
        val tampered = next.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertThrows(Exception::class.java) { receiver.decrypt(tampered) }
    }

    @Test fun recordsRejectReplayAndOutOfOrderDelivery() {
        val key = ByteArray(32) { 7 }
        val sender = EphemeralSessionCrypto.RecordCipher(key, key)
        val receiver = EphemeralSessionCrypto.RecordCipher(key, key)
        val first = sender.encrypt(byteArrayOf(1))
        val second = sender.encrypt(byteArrayOf(2))
        assertArrayEquals(byteArrayOf(1), receiver.decrypt(first))
        assertThrows(IllegalArgumentException::class.java) { receiver.decrypt(first) }
        assertArrayEquals(byteArrayOf(2), receiver.decrypt(second))
    }
}
