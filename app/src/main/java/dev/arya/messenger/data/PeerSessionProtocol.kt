package dev.arya.messenger.data

import dev.arya.messenger.security.EphemeralSessionCrypto
import dev.arya.messenger.security.IdentityKeys
import dev.arya.messenger.security.IdentitySignatures
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.security.SecureRandom

/** A peer whose identity was proven by the authenticated handshake, not by the address we used. */
data class AuthenticatedPeer(val publicKey: String, val displayName: String)

/** Which protocol rule rejected an attempt. Lets callers distinguish trust from reachability. */
enum class SessionFailure { UNTRUSTED_PEER, AUTH_FAILED, PROTOCOL, PAYLOAD_INVALID }

class PeerSessionException(val failure: SessionFailure, message: String) : IllegalStateException(message)

/**
 * The authenticated, encrypted one-message session.
 *
 * Runs over any reliable byte stream: a direct internet TCP connection, or a relay-spliced
 * connection. The handshake transcript, record layout, size limits and fail-closed behaviour are
 * the ones validated in Phase 4; this class only moved them out of the old LAN-specific owner.
 *
 * Transport security only. This is not an end-to-end messaging layer: it protects one connection
 * between two peers that are already in each other's verified contact list.
 */
class PeerSessionProtocol(
    private val localIdentity: suspend () -> IdentityEntity?,
    private val isTrustedPeer: suspend (publicKey: String) -> Boolean,
    private val onMessage: suspend (peerPublicKey: String, peerName: String, body: String) -> Unit,
    private val signIdentity: (alias: String, message: ByteArray) -> ByteArray = IdentityKeys::sign
) {

    companion object {
        const val MAGIC = 0x41525934
        const val VERSION = 1
        const val MAX_FRAME = 20_000
        const val TIMEOUT_MILLIS = 12_000
        private const val MAX_NAME = 64
        private const val MAX_KEY = 2048
        private const val MAX_BODY = 16_000
        private const val NONCE_SIZE = 32
    }

    /** Initiator side: the dialing peer sends one message and waits for the encrypted receipt. */
    suspend fun initiate(
        input: InputStream,
        output: OutputStream,
        identity: IdentityEntity,
        body: String
    ): AuthenticatedPeer {
        require(body.isNotBlank() && body.toByteArray().size <= MAX_BODY) { "پیام باید بین ۱ تا ۱۶۰۰۰ بایت باشد" }
        val source = DataInputStream(input)
        val sink = DataOutputStream(output)
        val ephemeral = EphemeralSessionCrypto.newEphemeralKeyPair()
        val nonce = ByteArray(NONCE_SIZE).also(SecureRandom()::nextBytes)
        sink.writeInt(MAGIC); sink.writeInt(VERSION)
        sink.writeUTF(identity.displayName.take(MAX_NAME)); sink.writeUTF(identity.publicKey.take(MAX_KEY))
        writeBytes(sink, ephemeral.public.encoded); writeBytes(sink, nonce); sink.flush()

        val peerName = source.readUTF().bounded(MAX_NAME)
        val peerKey = source.readUTF().bounded(MAX_KEY)
        val peerEphemeral = readBytes(source, 512)
        val peerNonce = readBytes(source, NONCE_SIZE)
        require(peerNonce.size == NONCE_SIZE) { "Nonce مقصد نامعتبر است" }
        val peerSignature = readBytes(source, 256)
        if (!isTrustedPeer(peerKey)) {
            throw PeerSessionException(
                SessionFailure.UNTRUSTED_PEER,
                "مقصد در مخاطبان تأییدشده نیست؛ ابتدا QR و اثر انگشت را تأیید کن"
            )
        }
        val transcript = transcript(
            identity.displayName, identity.publicKey, ephemeral.public.encoded, nonce,
            peerName, peerKey, peerEphemeral, peerNonce
        )
        if (!IdentitySignatures.verify(peerKey, transcript, peerSignature)) {
            throw PeerSessionException(SessionFailure.AUTH_FAILED, "امضای هویت مقصد معتبر نیست")
        }
        writeBytes(sink, signIdentity(identity.keyAlias, transcript)); sink.flush()

        val keys = EphemeralSessionCrypto.deriveSessionKeys(ephemeral.private, peerEphemeral, transcript, isInitiator = true)
        val records = EphemeralSessionCrypto.RecordCipher(keys.sendKey, keys.receiveKey)
        keys.sendKey.fill(0); keys.receiveKey.fill(0)
        try {
            writeRecord(sink, records, "ARYA-FINISH-v1".toByteArray(StandardCharsets.UTF_8))
            require(String(readRecord(source, records), StandardCharsets.UTF_8) == "ARYA-READY-v1") { "همتا نشست امن را تأیید نکرد" }
            writeRecord(sink, records, body.toByteArray(StandardCharsets.UTF_8))
            require(String(readRecord(source, records), StandardCharsets.UTF_8) == "ARYA-DELIVERED-v1") { "تأیید تحویل پیام دریافت نشد" }
        } finally {
            records.destroy()
        }
        return AuthenticatedPeer(peerKey, peerName)
    }

    /** Responder side: accepts one message from a peer that connected to us. */
    suspend fun respond(input: InputStream, output: OutputStream) {
        val identity = localIdentity() ?: error("هویت این دستگاه آماده نیست")
        val source = DataInputStream(input)
        val sink = DataOutputStream(output)
        require(source.readInt() == MAGIC && source.readInt() == VERSION) { "نسخه پروتکل پشتیبانی نمی‌شود" }
        val initiatorName = source.readUTF().bounded(MAX_NAME)
        val initiatorKey = source.readUTF().bounded(MAX_KEY)
        val initiatorEphemeral = readBytes(source, 512)
        val initiatorNonce = readBytes(source, NONCE_SIZE)
        require(initiatorNonce.size == NONCE_SIZE) { "Nonce نامعتبر است" }
        require(isTrustedPeer(initiatorKey)) { "هویت فرستنده در مخاطبان تأییدشده نیست" }

        val responderEphemeral = EphemeralSessionCrypto.newEphemeralKeyPair()
        val responderNonce = ByteArray(NONCE_SIZE).also(SecureRandom()::nextBytes)
        val transcript = transcript(
            initiatorName, initiatorKey, initiatorEphemeral, initiatorNonce,
            identity.displayName, identity.publicKey, responderEphemeral.public.encoded, responderNonce
        )
        val responderSignature = signIdentity(identity.keyAlias, transcript)
        sink.writeUTF(identity.displayName.take(MAX_NAME))
        sink.writeUTF(identity.publicKey.take(MAX_KEY))
        writeBytes(sink, responderEphemeral.public.encoded)
        writeBytes(sink, responderNonce)
        writeBytes(sink, responderSignature)
        sink.flush()

        val initiatorSignature = readBytes(source, 256)
        require(IdentitySignatures.verify(initiatorKey, transcript, initiatorSignature)) {
            "امضای احراز هویت فرستنده معتبر نیست"
        }
        val keys = EphemeralSessionCrypto.deriveSessionKeys(
            responderEphemeral.private, initiatorEphemeral, transcript, isInitiator = false
        )
        val records = EphemeralSessionCrypto.RecordCipher(keys.sendKey, keys.receiveKey)
        keys.sendKey.fill(0); keys.receiveKey.fill(0)
        try {
            require(String(readRecord(source, records), StandardCharsets.UTF_8) == "ARYA-FINISH-v1") {
                "تأیید نهایی نشست نامعتبر است"
            }
            writeRecord(sink, records, "ARYA-READY-v1".toByteArray(StandardCharsets.UTF_8))
            val body = String(readRecord(source, records), StandardCharsets.UTF_8)
            require(body.isNotBlank() && body.toByteArray().size <= MAX_BODY) { "پیام نامعتبر است" }
            onMessage(initiatorKey, initiatorName, body)
            writeRecord(sink, records, "ARYA-DELIVERED-v1".toByteArray(StandardCharsets.UTF_8))
        } finally {
            records.destroy()
        }
    }

    private fun transcript(
        iName: String, iKey: String, iEphemeral: ByteArray, iNonce: ByteArray,
        rName: String, rKey: String, rEphemeral: ByteArray, rNonce: ByteArray
    ): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { out ->
            out.writeUTF("ARYA-AUTHENTICATED-HANDSHAKE")
            out.writeInt(VERSION)
            out.writeUTF("initiator"); out.writeUTF(iName); out.writeUTF(iKey); writeBytes(out, iEphemeral); writeBytes(out, iNonce)
            out.writeUTF("responder"); out.writeUTF(rName); out.writeUTF(rKey); writeBytes(out, rEphemeral); writeBytes(out, rNonce)
        }
        bytes.toByteArray()
    }

    private fun readBytes(input: DataInputStream, max: Int): ByteArray {
        val size = input.readInt()
        require(size in 1..max) { "اندازه داده پروتکل نامعتبر است" }
        return ByteArray(size).also(input::readFully)
    }

    private fun writeBytes(output: DataOutputStream, bytes: ByteArray) {
        require(bytes.isNotEmpty() && bytes.size <= MAX_FRAME)
        output.writeInt(bytes.size); output.write(bytes)
    }

    private fun writeRecord(output: DataOutputStream, cipher: EphemeralSessionCrypto.RecordCipher, data: ByteArray) {
        val encrypted = cipher.encrypt(data)
        require(encrypted.size <= MAX_FRAME)
        output.writeInt(encrypted.size); output.write(encrypted); output.flush()
    }

    private fun readRecord(input: DataInputStream, cipher: EphemeralSessionCrypto.RecordCipher): ByteArray {
        val size = input.readInt()
        require(size in 36..MAX_FRAME) { "بسته رمزنگاری‌شده نامعتبر است" }
        return cipher.decrypt(ByteArray(size).also(input::readFully))
    }

    private fun String.bounded(max: Int): String = also { require(length in 1..max) { "فیلد متنی نامعتبر است" } }
}
