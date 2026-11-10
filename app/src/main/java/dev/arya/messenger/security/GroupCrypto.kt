package dev.arya.messenger.security

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Authenticated, epoch-bound local group message envelope. */
object GroupCrypto {
    private const val MAGIC = 0x41524735 // ARG5
    private const val VERSION = 1
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128
    private const val MAX_CIPHER_BYTES = 256 * 1024
    private const val MAX_KEY_BYTES = 32
    private const val MAX_ID_BYTES = 128
    private const val MAX_PUBLIC_KEY_BYTES = 2048

    data class Envelope(
        val groupId: String,
        val epoch: Long,
        val messageId: String,
        val senderPublicKey: String,
        val nonce: ByteArray,
        val ciphertext: ByteArray,
        val signature: ByteArray
    )

    fun newGroupKey(): ByteArray = ByteArray(MAX_KEY_BYTES).also(SecureRandom()::nextBytes)

    fun fingerprint(key: ByteArray): String {
        require(key.size == MAX_KEY_BYTES)
        return MessageDigest.getInstance("SHA-256").digest(key).joinToString("") { "%02x".format(it) }
    }

    fun encrypt(
        groupId: String, epoch: Long, messageId: String, plaintext: String,
        groupKey: ByteArray, senderPublicKey: String, sign: (ByteArray) -> ByteArray
    ): ByteArray {
        validateFields(groupId, epoch, messageId, senderPublicKey, groupKey)
        val plain = plaintext.toByteArray(Charsets.UTF_8)
        require(plain.size <= MAX_CIPHER_BYTES - 16) { "Group message is too large" }
        val nonce = ByteArray(NONCE_BYTES).also(SecureRandom()::nextBytes)
        val aad = aad(groupId, epoch, messageId, senderPublicKey)
        val ciphertext = try {
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(groupKey, "AES"), GCMParameterSpec(TAG_BITS, nonce))
                updateAAD(aad)
                doFinal(plain)
            }
        } finally { plain.fill(0) }
        val signed = signedBytes(groupId, epoch, messageId, senderPublicKey, nonce, ciphertext)
        val signature = sign(signed)
        require(signature.size in 8..256) { "Invalid signature length" }
        return encode(Envelope(groupId, epoch, messageId, senderPublicKey, nonce, ciphertext, signature))
    }

    fun decrypt(
        encoded: ByteArray, expectedGroupId: String, expectedEpoch: Long, expectedMessageId: String,
        allowedSenderPublicKeys: Set<String>, groupKey: ByteArray
    ): String {
        require(groupKey.size == MAX_KEY_BYTES) { "Invalid group key" }
        val envelope = decode(encoded)
        require(envelope.groupId == expectedGroupId) { "Wrong group" }
        require(envelope.epoch == expectedEpoch) { "Wrong group epoch" }
        require(envelope.messageId == expectedMessageId) { "Wrong message ID" }
        require(envelope.senderPublicKey in allowedSenderPublicKeys) { "Sender is not a member of this epoch" }
        val signed = signedBytes(envelope.groupId, envelope.epoch, envelope.messageId, envelope.senderPublicKey, envelope.nonce, envelope.ciphertext)
        require(IdentitySignatures.verify(envelope.senderPublicKey, signed, envelope.signature)) { "Invalid group message signature" }
        val plain = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(groupKey, "AES"), GCMParameterSpec(TAG_BITS, envelope.nonce))
            updateAAD(aad(envelope.groupId, envelope.epoch, envelope.messageId, envelope.senderPublicKey))
            doFinal(envelope.ciphertext)
        }
        return try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(plain)).toString()
        } finally { plain.fill(0) }
    }

    fun decode(encoded: ByteArray): Envelope {
        require(encoded.size in 64..(MAX_CIPHER_BYTES + 4096)) { "Invalid envelope size" }
        DataInputStream(ByteArrayInputStream(encoded)).use { input ->
            require(input.readInt() == MAGIC) { "Invalid group envelope magic" }
            require(input.readUnsignedByte() == VERSION) { "Unsupported group envelope version" }
            val groupId = readString(input, MAX_ID_BYTES)
            val epoch = input.readLong()
            val messageId = readString(input, MAX_ID_BYTES)
            val publicKey = readString(input, MAX_PUBLIC_KEY_BYTES)
            val nonce = readBytes(input, NONCE_BYTES, NONCE_BYTES)
            val ciphertext = readBytes(input, 16, MAX_CIPHER_BYTES)
            val signature = readBytes(input, 8, 256)
            require(input.available() == 0) { "Trailing envelope data" }
            require(epoch > 0)
            return Envelope(groupId, epoch, messageId, publicKey, nonce, ciphertext, signature)
        }
    }

    private fun encode(e: Envelope): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { out ->
            out.writeInt(MAGIC); out.writeByte(VERSION)
            writeString(out, e.groupId); out.writeLong(e.epoch); writeString(out, e.messageId)
            writeString(out, e.senderPublicKey); writeBytes(out, e.nonce); writeBytes(out, e.ciphertext); writeBytes(out, e.signature)
        }
        bytes.toByteArray()
    }

    private fun validateFields(groupId: String, epoch: Long, messageId: String, publicKey: String, key: ByteArray) {
        require(groupId.isNotBlank() && groupId.toByteArray().size <= MAX_ID_BYTES)
        require(epoch > 0)
        require(messageId.isNotBlank() && messageId.toByteArray().size <= MAX_ID_BYTES)
        require(publicKey.isNotBlank() && publicKey.toByteArray().size <= MAX_PUBLIC_KEY_BYTES)
        require(key.size == MAX_KEY_BYTES)
    }

    private fun aad(groupId: String, epoch: Long, messageId: String, publicKey: String) =
        signedBytes(groupId, epoch, messageId, publicKey, ByteArray(0), ByteArray(0))

    private fun signedBytes(groupId: String, epoch: Long, messageId: String, publicKey: String, nonce: ByteArray, ciphertext: ByteArray): ByteArray =
        ByteArrayOutputStream().use { bytes -> DataOutputStream(bytes).use { out ->
            out.writeInt(MAGIC); out.writeByte(VERSION); writeString(out, groupId); out.writeLong(epoch)
            writeString(out, messageId); writeString(out, publicKey); writeBytes(out, nonce); writeBytes(out, ciphertext)
        }; bytes.toByteArray() }

    private fun writeString(out: DataOutputStream, value: String) = writeBytes(out, value.toByteArray(Charsets.UTF_8))
    private fun writeBytes(out: DataOutputStream, value: ByteArray) { out.writeInt(value.size); out.write(value) }
    private fun readString(input: DataInputStream, max: Int): String {
        val bytes = readBytes(input, 1, max)
        return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    }
    private fun readBytes(input: DataInputStream, min: Int, max: Int): ByteArray {
        val size = input.readInt(); require(size in min..max && size <= input.available()) { "Invalid envelope field length" }
        return ByteArray(size).also(input::readFully)
    }
}
