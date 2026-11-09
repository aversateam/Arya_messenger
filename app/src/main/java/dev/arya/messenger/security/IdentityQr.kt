package dev.arya.messenger.security

import org.json.JSONObject
import java.security.MessageDigest
import java.util.Base64

/** Versioned public identity card. It contains no private key or network address. */
object IdentityQr {
    data class Card(val displayName: String, val publicKey: String, val fingerprint: String)

    fun fingerprint(publicKeyBase64: String): String {
        val keyBytes = Base64.getDecoder().decode(publicKeyBase64)
        val digest = MessageDigest.getInstance("SHA-256").digest(keyBytes)
        return digest.joinToString("") { "%02X".format(it.toInt() and 0xFF) }.chunked(4).joinToString(" ")
    }

    fun encode(displayName: String, publicKey: String): String =
        JSONObject().put("type", "arya-contact").put("version", 1)
            .put("name", displayName.trim().take(64)).put("publicKey", publicKey)
            .put("fingerprint", fingerprint(publicKey)).toString()

    fun decode(raw: String): Card {
        require(raw.length <= 4096) { "داده QR بیش از حد بزرگ است" }
        val json = JSONObject(raw)
        require(json.optString("type") == "arya-contact") { "این QR متعلق به مخاطب آریا نیست" }
        require(json.optInt("version") == 1) { "نسخه QR پشتیبانی نمی‌شود" }
        val name = json.getString("name").trim().take(64)
        val key = json.getString("publicKey")
        require(name.isNotBlank() && key.isNotBlank()) { "اطلاعات هویت ناقص است" }
        val actual = fingerprint(key)
        require(json.optString("fingerprint") == actual) { "اثر انگشت QR معتبر نیست" }
        return Card(name, key, actual)
    }
}
