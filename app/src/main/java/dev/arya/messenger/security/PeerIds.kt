package dev.arya.messenger.security

import java.util.Base64

/**
 * Stable identifiers derived from a peer's root identity key.
 *
 * The same peer produces the same identifier on both sides of a conversation, so inbound and
 * outbound traffic is stored in one thread. The digest reuses [IdentityQr.fingerprint], which is
 * already covered by tests, instead of introducing a second hashing scheme.
 */
object PeerIds {
    private const val PEER_PREFIX = "pk:"
    private const val CONVERSATION_PREFIX = "dm-"
    private const val MAX_PUBLIC_KEY_CHARS = 2048

    /** Returns the trimmed Base64 key, rejecting input that is not a usable P-256 identity key. */
    fun normalize(publicKeyBase64: String): String {
        val key = publicKeyBase64.trim()
        require(key.isNotEmpty()) { "کلید عمومی خالی است" }
        require(key.length <= MAX_PUBLIC_KEY_CHARS) { "کلید عمومی بیش از حد بزرگ است" }
        val decoded = runCatching { Base64.getDecoder().decode(key) }
            .getOrElse { throw IllegalArgumentException("کلید عمومی Base64 معتبر نیست") }
        require(decoded.isNotEmpty()) { "کلید عمومی معتبر نیست" }
        require(IdentitySignatures.isP256PublicKey(key)) { "کلید عمومی باید P-256 باشد" }
        return key
    }

    /** Lowercase hex SHA-256 over the SPKI DER, without the display group separators. */
    fun digest(publicKeyBase64: String): String =
        IdentityQr.fingerprint(normalize(publicKeyBase64)).replace(" ", "").lowercase()

    fun peerId(publicKeyBase64: String): String = PEER_PREFIX + digest(publicKeyBase64)

    fun conversationId(publicKeyBase64: String): String = CONVERSATION_PREFIX + digest(publicKeyBase64)
}
