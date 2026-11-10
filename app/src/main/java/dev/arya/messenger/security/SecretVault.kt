package dev.arya.messenger.security

/**
 * Seals short secrets with a device-bound key.
 *
 * Sealed layout is `nonce(12) || ciphertext || tag(16)`, so callers can validate lengths
 * without knowing anything about the backing key store.
 */
interface SecretVault {
    fun seal(plaintext: ByteArray, aad: ByteArray): ByteArray
    fun open(sealed: ByteArray, aad: ByteArray): ByteArray
}
