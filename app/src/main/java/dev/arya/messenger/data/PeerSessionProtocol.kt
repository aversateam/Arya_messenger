package dev.arya.messenger.data

import dev.arya.messenger.security.EphemeralSessionCrypto
import dev.arya.messenger.security.IdentityKeys
import dev.arya.messenger.security.IdentitySignatures
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.SecureRandom

/** A peer whose identity was proven by the authenticated handshake, not by the dialled address. */
data class AuthenticatedPeer(val publicKey: String, val displayName: String)

/** Which protocol rule rejected an attempt. Lets callers distinguish trust from reachability. */
enum class LanFailure { UNTRUSTED_PEER, AUTH_FAILED, PROTOCOL, PAYLOAD_INVALID }

class LanTransportException(val failure: LanFailure, message: String) : IllegalStateException(message)

/**
 * The Phase 4 authenticated, encrypted one-message session.
 *
 * Extracted from [LanPeerTransport] so the identical wire protocol can run over any reliable byte
 * stream: a LAN socket, a direct internet TCP dial, or a relay-spliced connection. The handshake
 * transcript, record layout, size limits and failure behaviour are unchanged.
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

    /** Initiator side: dialed peer sends one message and waits for the encrypted receipt. */
    suspend fun initiate(socket: Socket, identity: IdentityEntity, body: String): AuthenticatedPeer {
        require(body.isNotBlank() && body.toByteArray().size <= MAX_BODY) { "پیام باید بین ۱ تا ۱۶۰۰۰ بایت باشد" }
        socket.soTimeout = TIMEOUT_MILLIS
        val input = DataInputStream(socket.getInputStream())
        val output = DataOutputStream(socket.getOutputStream())
        val ephemeral = EphemeralSessionCrypto.newEphemeralKeyPair()
        val nonce = ByteArray(NONCE_SIZE).also(SecureRandom()::nextBytes)
        output.writeInt(MAGIC); output.writeInt(VERSION)
        output.writeUTF(identity.displayName.take(MAX_NAME)); output.writeUTF(identity.publicKey.take(MAX_KEY))
        writeBytes(output, ephemeral.public.encoded); writeBytes(output, nonce); output.flush()

        val peerName = input.readUTF().bounded(MAX_NAME)
        val peerKey = input.readUTF().bounded(MAX_KEY)
        val peerEphemeral = readBytes(input, 512)
        val peerNonce = readBytes(input, NONCE_SIZE)
        require(peerNonce.size == NONCE_SIZE) { "Nonce مقصد نامعتبر است" }
        val peerSignature = readBytes(input, 256)
        if (!isTrustedPeer(peerKey)) {
            throw LanTransportException(
                LanFailure.UNTRUSTED_PEER,
                "مقصد در مخاطبان تأییدشده نیست؛ ابتدا QR و اثر انگشت را تأیید کن"
            )
        }
        val transcript = transcript(
            identity.displayName, identity.publicKey, ephemeral.public.encoded, nonce,
            peerName, peerKey, peerEphemeral, peerNonce
        )
        if (!IdentitySignatures.verify(peerKey, transcript, peerSignature)) {
            throw LanTransportException(LanFailure.AUTH_FAILED, "امضای هویت مقصد معتبر نیست")
        }
        writeBytes(output, signIdentity(identity.keyAlias, transcript)); output.flush()

        val keys = EphemeralSessionCrypto.deriveSessionKeys(ephemeral.private, peerEphemeral, transcript, isInitiator = true)
        val records = EphemeralSessionCrypto.RecordCipher(keys.sendKey, keys.receiveKey)
        keys.sendKey.fill(0); keys.receiveKey.fill(0)
        try {
            writeRecord(output, records, "ARYA-FINISH-v1".toByteArray(StandardCharsets.UTF_8))
            require(String(readRecord(input, records), StandardCharsets.UTF_8) == "ARYA-READY-v1") { "همتا نشست امن را تأیید نکرد" }
            writeRecord(output, records, body.toByteArray(StandardCharsets.UTF_8))
            require(String(readRecord(input, records), StandardCharsets.UTF_8) == "ARYA-DELIVERED-v1") { "تأیید تحویل پیام دریافت نشد" }
        } finally {
            records.destroy()
        }
        return AuthenticatedPeer(peerKey, peerName)
    }

    /** Responder side: accepts one message from a peer that connected to us. */
    suspend fun respond(socket: Socket) {
        socket.soTimeout = TIMEOUT_MILLIS
        val identity = localIdentity() ?: error("هویت این دستگاه آماده نیست")
        val input = DataInputStream(socket.getInputStream())
        val output = DataOutputStream(socket.getOutputStream())
        require(input.readInt() == MAGIC && input.readInt() == VERSION) { "نسخه پروتکل پشتیبانی نمی‌شود" }
        val initiatorName = input.readUTF().bounded(MAX_NAME)
        val initiatorKey = input.readUTF().bounded(MAX_KEY)
        val initiatorEphemeral = readBytes(input, 512)
        val initiatorNonce = readBytes(input, NONCE_SIZE)
        require(initiatorNonce.size == NONCE_SIZE) { "Nonce نامعتبر است" }
        require(isTrustedPeer(initiatorKey)) { "هویت فرستنده در مخاطبان تأییدشده نیست" }

        val responderEphemeral = EphemeralSessionCrypto.newEphemeralKeyPair()
        val responderNonce = ByteArray(NONCE_SIZE).also(SecureRandom()::nextBytes)
        val transcript = transcript(
            initiatorName, initiatorKey, initiatorEphemeral, initiatorNonce,
            identity.displayName, identity.publicKey, responderEphemeral.public.encoded, responderNonce
        )
        val responderSignature = signIdentity(identity.keyAlias, transcript)
        output.writeUTF(identity.displayName.take(MAX_NAME))
        output.writeUTF(identity.publicKey.take(MAX_KEY))
        writeBytes(output, responderEphemeral.public.encoded)
        writeBytes(output, responderNonce)
        writeBytes(output, responderSignature)
        output.flush()

        val initiatorSignature = readBytes(input, 256)
        require(IdentitySignatures.verify(initiatorKey, transcript, initiatorSignature)) {
            "امضای احراز هویت فرستنده معتبر نیست"
        }
        val keys = EphemeralSessionCrypto.deriveSessionKeys(
            responderEphemeral.private, initiatorEphemeral, transcript, isInitiator = false
        )
        val records = EphemeralSessionCrypto.RecordCipher(keys.sendKey, keys.receiveKey)
        keys.sendKey.fill(0); keys.receiveKey.fill(0)
        try {
            require(String(readRecord(input, records), StandardCharsets.UTF_8) == "ARYA-FINISH-v1") {
                "تأیید نهایی نشست نامعتبر است"
            }
            writeRecord(output, records, "ARYA-READY-v1".toByteArray(StandardCharsets.UTF_8))
            val body = String(readRecord(input, records), StandardCharsets.UTF_8)
            require(body.isNotBlank() && body.toByteArray().size <= MAX_BODY) { "پیام نامعتبر است" }
            onMessage(initiatorKey, initiatorName, body)
            writeRecord(output, records, "ARYA-DELIVERED-v1".toByteArray(StandardCharsets.UTF_8))
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
