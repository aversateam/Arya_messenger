package dev.arya.messenger

import dev.arya.messenger.security.IdentitySignatures
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64

class IdentitySignaturesTest {
    private fun newIdentity() = KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec("secp256r1"))
        generateKeyPair()
    }

    @Test fun verifiesSignatureFromMatchingP256PublicKey() {
        val pair = newIdentity()
        val message = "arya-phase4-authentication-test".toByteArray()
        val signature = java.security.Signature.getInstance("SHA256withECDSA").run {
            initSign(pair.private)
            update(message)
            sign()
        }
        val publicKey = Base64.getEncoder().encodeToString(pair.public.encoded)

        assertTrue(IdentitySignatures.verify(publicKey, message, signature))
    }

    @Test fun rejectsModifiedMessage() {
        val pair = newIdentity()
        val signature = java.security.Signature.getInstance("SHA256withECDSA").run {
            initSign(pair.private)
            update("original".toByteArray())
            sign()
        }
        val publicKey = Base64.getEncoder().encodeToString(pair.public.encoded)

        assertFalse(IdentitySignatures.verify(publicKey, "modified".toByteArray(), signature))
    }

    @Test fun rejectsSignatureFromAnotherIdentity() {
        val signer = newIdentity()
        val other = newIdentity()
        val message = "identity-binding".toByteArray()
        val signature = java.security.Signature.getInstance("SHA256withECDSA").run {
            initSign(signer.private)
            update(message)
            sign()
        }
        val otherPublicKey = Base64.getEncoder().encodeToString(other.public.encoded)

        assertFalse(IdentitySignatures.verify(otherPublicKey, message, signature))
    }

    @Test fun rejectsMalformedPublicKeyAndEmptySignature() {
        assertFalse(IdentitySignatures.verify("not-base64", byteArrayOf(1), byteArrayOf(1)))
        val pair = newIdentity()
        val publicKey = Base64.getEncoder().encodeToString(pair.public.encoded)
        assertFalse(IdentitySignatures.verify(publicKey, byteArrayOf(1), byteArrayOf()))
    }
}
