package dev.arya.messenger.security

import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPublicKeySpec
import java.security.spec.X509EncodedKeySpec
import java.security.interfaces.ECPublicKey
import java.util.Base64

/** Verification helpers for Arya's P-256 identity signatures. */
object IdentitySignatures {
    /** Verifies a SHA-256/ECDSA signature against the P-256 public key in an identity card. */
    fun verify(publicKeyBase64: String, message: ByteArray, signature: ByteArray): Boolean = runCatching {
        require(publicKeyBase64.isNotBlank())
        require(signature.isNotEmpty())
        val encodedKey = Base64.getDecoder().decode(publicKeyBase64)
        val publicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(encodedKey)) as ECPublicKey
        require(publicKey.params.isP256()) { "Identity key must use secp256r1" }
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(publicKey)
            update(message)
            verify(signature)
        }
    }.getOrDefault(false)

    /** Structural check that a Base64 key is a P-256 public key usable as an Arya identity. */
    fun isP256PublicKey(publicKeyBase64: String): Boolean = runCatching {
        val encodedKey = Base64.getDecoder().decode(publicKeyBase64)
        val publicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(encodedKey)) as ECPublicKey
        publicKey.params.isP256()
    }.getOrDefault(false)

    private fun ECParameterSpec.isP256(): Boolean {
        val expected = AlgorithmParameters.getInstance("EC").run {
            init(ECGenParameterSpec("secp256r1"))
            getParameterSpec(ECParameterSpec::class.java)
        }
        return curve == expected.curve && generator == expected.generator &&
            order == expected.order && cofactor == expected.cofactor
    }
}
