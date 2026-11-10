package dev.arya.messenger.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.util.Base64

/** Device-bound P-256 signing identity. The private key never leaves Android Keystore. */
object IdentityKeys {
    data class PublicIdentity(val alias: String, val encodedPublicKey: String)

    fun create(alias: String): PublicIdentity {
        require(alias.isNotBlank()) { "Key alias must not be blank" }
        val store = keyStore()
        if (!store.containsAlias(alias)) {
            val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
            generator.initialize(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    .setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .build()
            )
            generator.generateKeyPair()
        }
        val publicKey = store.getCertificate(alias)?.publicKey
            ?: error("No public certificate found for identity alias")
        return PublicIdentity(alias, Base64.getEncoder().encodeToString(publicKey.encoded))
    }

    /** Signs caller-provided protocol bytes with the non-exportable Android Keystore key. */
    fun sign(alias: String, message: ByteArray): ByteArray {
        require(alias.isNotBlank()) { "Key alias must not be blank" }
        val privateKey = keyStore().getKey(alias, null) as? java.security.PrivateKey
            ?: error("No private key found for identity alias")
        return Signature.getInstance("SHA256withECDSA").run {
            initSign(privateKey)
            update(message)
            sign()
        }
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
}
