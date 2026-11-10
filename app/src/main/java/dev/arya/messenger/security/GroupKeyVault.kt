package dev.arya.messenger.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Wraps group epoch keys with a non-exportable Android Keystore AES key. */
object GroupKeyVault {
    private const val ALIAS = "arya-group-key-wrap-v1"
    private const val NONCE_SIZE = 12

    fun seal(groupId: String, epoch: Long, rawKey: ByteArray): ByteArray {
        require(rawKey.size == 32 && epoch > 0)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        // Let Android Keystore generate the IV; supplying our own IV can be rejected
        // when randomized encryption is enforced for Keystore keys.
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val nonce = requireNotNull(cipher.iv) { "Android Keystore did not provide a GCM IV" }
        require(nonce.size == NONCE_SIZE) { "Unexpected Android Keystore GCM IV size" }
        cipher.updateAAD(aad(groupId, epoch))
        return nonce + cipher.doFinal(rawKey)
    }

    fun open(groupId: String, epoch: Long, sealed: ByteArray): ByteArray {
        require(epoch > 0 && sealed.size == NONCE_SIZE + 32 + 16)
        val nonce = sealed.copyOfRange(0, NONCE_SIZE)
        val ciphertext = sealed.copyOfRange(NONCE_SIZE, sealed.size)
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, nonce))
            updateAAD(aad(groupId, epoch)); doFinal(ciphertext)
        }
    }

    private fun aad(groupId: String, epoch: Long) = "arya-group-key|$groupId|$epoch".toByteArray(Charsets.UTF_8)
    private fun getOrCreateKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
            generateKey()
        }
    }
}
