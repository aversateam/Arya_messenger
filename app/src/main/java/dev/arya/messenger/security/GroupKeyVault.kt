package dev.arya.messenger.security

/** Wraps group epoch keys with a non-exportable Android Keystore AES key. */
object GroupKeyVault {
    private const val ALIAS = "arya-group-key-wrap-v1"

    private val vault: SecretVault = KeystoreAesVault(ALIAS)

    fun seal(groupId: String, epoch: Long, rawKey: ByteArray): ByteArray {
        require(rawKey.size == 32 && epoch > 0)
        return vault.seal(rawKey, aad(groupId, epoch))
    }

    fun open(groupId: String, epoch: Long, sealed: ByteArray): ByteArray {
        require(epoch > 0 && sealed.size == KeystoreAesVault.NONCE_SIZE + 32 + KeystoreAesVault.TAG_SIZE)
        return vault.open(sealed, aad(groupId, epoch))
    }

    private fun aad(groupId: String, epoch: Long) = "arya-group-key|$groupId|$epoch".toByteArray(Charsets.UTF_8)
}
