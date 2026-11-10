package dev.arya.messenger.security

import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.interfaces.ECPrivateKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Cryptographic building blocks for one authenticated, ephemeral LAN session.
 * The caller MUST authenticate/sign the complete handshake transcript with the
 * long-term identity keys and MUST compare both identities with verified contacts
 * before using the returned keys. This class does not perform trust decisions.
 */
object EphemeralSessionCrypto {
    private const val KEY_BYTES = 32
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128
    private const val MAX_CIPHERTEXT_BYTES = 16_384 + 64
    private val random = SecureRandom()

    data class SessionKeys(val sendKey: ByteArray, val receiveKey: ByteArray)

    /** Creates a fresh P-256 ECDH key pair. Never reuse it for another session. */
    fun newEphemeralKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec("secp256r1"), random)
        generateKeyPair()
    }

    /**
     * Derives role-separated AES-256 keys from a fresh ECDH exchange.
     * Nonces and public keys must be in canonical initiator/responder order on both devices.
     * `transcript` must include protocol version, both identity keys, both ephemeral keys,
     * both nonces, and roles; it is also hashed into the KDF salt.
     */
    fun deriveSessionKeys(
        localPrivateKey: java.security.PrivateKey,
        remoteEphemeralPublicKey: ByteArray,
        transcript: ByteArray,
        isInitiator: Boolean
    ): SessionKeys {
        require(transcript.isNotEmpty() && transcript.size <= 16_384) { "Invalid handshake transcript" }
        val remoteKey = KeyFactory.getInstance("EC")
            .generatePublic(X509EncodedKeySpec(remoteEphemeralPublicKey)) as ECPublicKey
        val localParams = (localPrivateKey as? ECPrivateKey)?.params
            ?: error("Local ephemeral key must be an EC private key")
        require(remoteKey.params.curve == localParams.curve &&
            remoteKey.params.generator == localParams.generator &&
            remoteKey.params.order == localParams.order &&
            remoteKey.params.cofactor == localParams.cofactor &&
            remoteKey.params.order.bitLength() == 256) { "Peer ephemeral key must use P-256" }
        val sharedSecret = KeyAgreement.getInstance("ECDH").run {
            init(localPrivateKey)
            doPhase(remoteKey, true)
            generateSecret()
        }
        try {
            val salt = MessageDigest.getInstance("SHA-256").digest(transcript)
            val prk = hmac(salt, sharedSecret)
            try {
                val expanded = hkdfExpand(prk, "arya/phase4/lan-session/v1".toByteArray(), KEY_BYTES * 2)
                try {
                    val initiatorToResponder = expanded.copyOfRange(0, KEY_BYTES)
                    val responderToInitiator = expanded.copyOfRange(KEY_BYTES, KEY_BYTES * 2)
                    return if (isInitiator) SessionKeys(initiatorToResponder, responderToInitiator)
                    else SessionKeys(responderToInitiator, initiatorToResponder)
                } finally { expanded.fill(0) }
            } finally { prk.fill(0) }
        } finally {
            sharedSecret.fill(0)
        }
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256"))
        doFinal(data)
    }

    private fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..(255 * 32))
        val output = ByteArray(length)
        var previous = byteArrayOf()
        var offset = 0
        var counter = 1
        while (offset < length) {
            previous = hmac(prk, previous + info + byteArrayOf(counter.toByte()))
            val count = minOf(previous.size, length - offset)
            previous.copyInto(output, offset, 0, count)
            offset += count
            counter++
        }
        previous.fill(0)
        return output
    }

    /** A strictly ordered, replay-rejecting AES-GCM record layer for one TCP session. */
    class RecordCipher(sendKey: ByteArray, receiveKey: ByteArray) {
        private val outboundKey = sendKey.copyOf().also { require(it.size == KEY_BYTES) }
        private val inboundKey = receiveKey.copyOf().also { require(it.size == KEY_BYTES) }
        private var sendSequence = 0L
        private var receiveSequence = 0L

        @Synchronized
        fun encrypt(plaintext: ByteArray): ByteArray {
            require(plaintext.size <= 16_000) { "Message is too large" }
            check(sendSequence >= 0 && sendSequence < Long.MAX_VALUE) { "Session sequence exhausted" }
            val sequence = sendSequence
            val sequenceBytes = ByteBuffer.allocate(8).putLong(sequence).array()
            val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
            val cipherText = Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(outboundKey, "AES"), GCMParameterSpec(TAG_BITS, nonce))
                updateAAD(sequenceBytes)
                doFinal(plaintext)
            }
            sendSequence++
            return sequenceBytes + nonce + cipherText
        }

        @Synchronized
        fun decrypt(record: ByteArray): ByteArray {
            require(record.size in (8 + NONCE_BYTES + 16)..(8 + NONCE_BYTES + MAX_CIPHERTEXT_BYTES)) {
                "Invalid encrypted record size"
            }
            val buffer = ByteBuffer.wrap(record)
            val sequence = buffer.long
            require(sequence == receiveSequence) { "Out-of-order or replayed record" }
            val nonce = ByteArray(NONCE_BYTES).also(buffer::get)
            val ciphertext = ByteArray(buffer.remaining()).also(buffer::get)
            val sequenceBytes = ByteBuffer.allocate(8).putLong(sequence).array()
            val plaintext = Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(inboundKey, "AES"), GCMParameterSpec(TAG_BITS, nonce))
                updateAAD(sequenceBytes)
                doFinal(ciphertext)
            }
            receiveSequence++
            return plaintext
        }

        /** Best-effort in-memory key erasure when a session is closed. */
        @Synchronized
        fun destroy() {
            outboundKey.fill(0)
            inboundKey.fill(0)
        }
    }
}
