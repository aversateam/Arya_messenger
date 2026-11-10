package dev.arya.messenger.security

import org.json.JSONObject
import java.security.MessageDigest
import java.util.Base64

/**
 * Versioned public identity card. It contains no private key.
 *
 * Version 1 carries only the identity. Version 2 additionally carries optional reachability hints:
 * a direct endpoint the owner believes is dialable, and a relay they are willing to be reached
 * through. Both hints are unauthenticated data — the identity is still proven by the signed
 * handshake, never by the address.
 */
object IdentityQr {
    private const val LINK_PREFIX = "arya://v2/"

    data class Card(
        val displayName: String,
        val publicKey: String,
        val fingerprint: String,
        val endpoint: String? = null,
        val relay: String? = null
    )

    fun fingerprint(publicKeyBase64: String): String {
        val keyBytes = Base64.getDecoder().decode(publicKeyBase64)
        val digest = MessageDigest.getInstance("SHA-256").digest(keyBytes)
        return digest.joinToString("") { "%02X".format(it.toInt() and 0xFF) }.chunked(4).joinToString(" ")
    }

    fun encode(
        displayName: String,
        publicKey: String,
        endpoint: String? = null,
        relay: String? = null
    ): String {
        val direct = endpoint?.trim()?.takeIf { isValidHint(it) }
        val via = relay?.trim()?.takeIf { isValidHint(it) }
        return JSONObject()
            .put("type", "arya-contact")
            .put("version", if (direct == null && via == null) 1 else 2)
            .put("name", displayName.trim().take(64))
            .put("publicKey", publicKey)
            .put("fingerprint", fingerprint(publicKey))
            .apply {
                direct?.let { put("endpoint", it) }
                via?.let { put("relay", it) }
            }
            .toString()
    }

    /**
     * A single shareable invitation string.
     *
     * The QR code, the copied link, and a pasted message are all the same payload, so there is
     * exactly one way to connect: exchange an invitation.
     */
    fun link(
        displayName: String,
        publicKey: String,
        endpoint: String? = null,
        relay: String? = null
    ): String {
        val json = encode(displayName, publicKey, endpoint, relay)
        val encoded = Base64.getUrlEncoder().encodeToString(json.toByteArray(Charsets.UTF_8))
        return LINK_PREFIX + encoded
    }

    /** Accepts a QR payload, an invitation link, or a bare link body. */
    fun decodeInvite(raw: String): Card {
        val text = raw.trim()
        if (text.isEmpty()) throw IllegalArgumentException("دعوت خالی است")
        val json = if (text.startsWith(LINK_PREFIX)) {
            val encoded = text.removePrefix(LINK_PREFIX)
            String(Base64.getUrlDecoder().decode(encoded), Charsets.UTF_8)
        } else {
            text
        }
        return decode(json)
    }

    fun decode(raw: String): Card {
        require(raw.length <= 4096) { "داده QR بیش از حد بزرگ است" }
        val json = JSONObject(raw)
        require(json.optString("type") == "arya-contact") { "این QR متعلق به مخاطب آریا نیست" }
        val version = json.optInt("version")
        require(version == 1 || version == 2) { "نسخه QR پشتیبانی نمی‌شود" }
        val name = json.getString("name").trim().take(64)
        val key = json.getString("publicKey")
        require(name.isNotBlank() && key.isNotBlank()) { "اطلاعات هویت ناقص است" }
        val actual = fingerprint(key)
        require(json.optString("fingerprint") == actual) { "اثر انگشت QR معتبر نیست" }
        return Card(
            displayName = name,
            publicKey = key,
            fingerprint = actual,
            endpoint = json.optString("endpoint").takeIf { isValidHint(it) },
            relay = json.optString("relay").takeIf { isValidHint(it) }
        )
    }

    /** Strict "host:port" shape check. Anything else is dropped rather than reaching config. */
    private fun isValidHint(raw: String): Boolean {
        val value = raw.trim()
        if (value.length !in 3..255) return false
        if (value.any { it.isWhitespace() || it == '/' || it == '|' }) return false
        val separator = value.lastIndexOf(':')
        if (separator <= 0 || separator == value.lastIndex) return false
        val port = value.substring(separator + 1).toIntOrNull() ?: return false
        return port in 1..65535
    }
}
