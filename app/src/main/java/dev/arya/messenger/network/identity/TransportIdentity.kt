package dev.arya.messenger.network.identity

import dev.arya.messenger.data.IdentityEntity
import dev.arya.messenger.security.IdentitySignatures
import dev.arya.messenger.security.SecretVault
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * The transport keypair used by the internet networking layer.
 *
 * The root identity is a non-exportable P-256 key in Android Keystore, so no third-party host can
 * be handed it. A libp2p host therefore needs its own keypair. That second key is bound to the
 * root identity by a P-256 signature over a domain-separated transcript, which mirrors libp2p's
 * own rule that the Noise static key is authenticated by the identity key.
 *
 * Nothing here changes the fingerprint, QR card, contact table, or group membership history: the
 * root P-256 key remains the only peer identity the rest of the app knows about.
 *
 * The concrete transport key type is intentionally not cached here. Phase 6.1 provisions a P-256
 * key because it is available on every supported API level; the libp2p spike determines whether
 * the chosen host accepts it or needs Ed25519 instead. Note that JCA Ed25519 requires API 33+,
 * above this app's minSdk 26.
 */
class TransportIdentity(
    private val identity: suspend () -> IdentityEntity?,
    private val save: suspend (publicKeyBase64: String, sealedPrivateKey: ByteArray) -> Unit,
    private val vault: SecretVault,
    private val signIdentity: (String, ByteArray) -> ByteArray
) {

    data class Provisioned(
        val transportPublicKey: ByteArray,
        val bindingSignature: ByteArray,
        val identityPublicKey: String
    )

    suspend fun ensureProvisioned(): Provisioned {
        val local = requireNotNull(identity()) { "هویت محلی هنوز ساخته نشده است" }
        val storedPublic = local.transportPublicKey
        val storedSealed = local.transportKeySealed

        if (storedPublic != null && storedSealed != null) {
            val privateKey = loadPrivateKey(local, storedSealed)
            val publicKey = loadPublicKey(storedPublic)
            check(holdsKeyPair(privateKey, publicKey)) {
                "کلید انتقال با کلید عمومی ذخیره‌شده هم‌خوانی ندارد"
            }
            val transportPublic = publicKey.encoded
            return Provisioned(transportPublic, bindingSignature(local, transportPublic), local.publicKey)
        }

        val pair = KeyPairGenerator.getInstance(KEY_ALGORITHM).run {
            initialize(ECGenParameterSpec(CURVE))
            generateKeyPair()
        }
        val transportPublic = pair.public.encoded
        val sealedPrivate = vault.seal(pair.private.encoded, aad(local.publicKey))
        save(Base64.getEncoder().encodeToString(transportPublic), sealedPrivate)
        return Provisioned(transportPublic, bindingSignature(local, transportPublic), local.publicKey)
    }

    private fun loadPrivateKey(local: IdentityEntity, sealed: ByteArray): PrivateKey = KeyFactory
        .getInstance(KEY_ALGORITHM)
        .generatePrivate(PKCS8EncodedKeySpec(vault.open(sealed, aad(local.publicKey))))

    private fun loadPublicKey(encodedBase64: String): PublicKey = KeyFactory
        .getInstance(KEY_ALGORITHM)
        .generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(encodedBase64)))

    /** Proves the sealed private key actually belongs to the stored public key. */
    private fun holdsKeyPair(privateKey: PrivateKey, publicKey: PublicKey): Boolean = runCatching {
        val probe = SELF_CHECK_PROBE.toByteArray(Charsets.UTF_8)
        val signature = Signature.getInstance(SIGNATURE_ALGORITHM).run {
            initSign(privateKey)
            update(probe)
            sign()
        }
        Signature.getInstance(SIGNATURE_ALGORITHM).run {
            initVerify(publicKey)
            update(probe)
            verify(signature)
        }
    }.getOrDefault(false)

    private fun bindingSignature(local: IdentityEntity, transportPublicKey: ByteArray): ByteArray =
        signIdentity(local.keyAlias, bindingBytes(local.publicKey, transportPublicKey))

    private fun aad(identityPublicKey: String): ByteArray =
        (AAD_CONTEXT + identityPublicKey).toByteArray(Charsets.UTF_8)

    companion object {
        const val TRANSPORT_KEY_ALIAS = "arya-transport-key-wrap-v1"

        private const val KEY_ALGORITHM = "EC"
        private const val CURVE = "secp256r1"
        private const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
        private const val BINDING_CONTEXT = "arya/phase6/transport-key-binding/v1"
        private const val AAD_CONTEXT = "arya/phase6/transport-key|"
        private const val SELF_CHECK_PROBE = "arya/phase6/transport-key-selfcheck/v1"

        /**
         * The exact bytes a peer must verify before trusting that a transport key belongs to a
         * root identity. Length-prefixed and domain-separated so no field can be shifted into
         * another field's position.
         */
        fun bindingBytes(identityPublicKey: String, transportPublicKey: ByteArray): ByteArray =
            ByteArrayOutputStream().use { bytes ->
                DataOutputStream(bytes).use { out ->
                    out.writeUTF(BINDING_CONTEXT)
                    out.writeUTF(identityPublicKey)
                    out.writeInt(transportPublicKey.size)
                    out.write(transportPublicKey)
                }
                bytes.toByteArray()
            }

        fun verifyBinding(
            identityPublicKey: String,
            transportPublicKey: ByteArray,
            signature: ByteArray
        ): Boolean = IdentitySignatures.verify(
            identityPublicKey,
            bindingBytes(identityPublicKey, transportPublicKey),
            signature
        )
    }
}
