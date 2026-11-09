package dev.arya.messenger.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.util.Base64

/** Device-bound signing identity. The private key never leaves Android Keystore. */
object IdentityKeys {
    data class PublicIdentity(val alias: String, val encodedPublicKey: String)

    fun create(alias: String): PublicIdentity {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
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
        val publicKey = store.getCertificate(alias).publicKey.encoded
        return PublicIdentity(alias, Base64.getEncoder().encodeToString(publicKey))
    }
}
