package dev.arya.messenger.security

import java.security.MessageDigest

/**
 * Rotating handle a device uses to accept relay pairings.
 *
 * A relay has to match two connections, which means both sides must agree on something the node can
 * key on. Publishing the permanent identity digest there would let any node link all of a device's
 * traffic to one stable pseudonym and slowly build a social graph.
 *
 * Instead the handle is a hash of the recipient's own identity digest and the current time window.
 * Any sender who already knows the recipient's public key can compute the same handle, the node
 * cannot link it to an identity without already knowing that key, and it changes completely at every
 * window boundary. A node therefore sees "some devices are attached to handle X right now", not
 * "device A is online".
 *
 * The handle is not a secret and it authenticates nothing. Identity is still proven by the signed
 * session that runs after pairing.
 */
object RendezvousToken {

    const val WINDOW_MILLIS = 15 * 60 * 1000L
    const val SIZE = 32
    private const val DOMAIN = "arya/phase6/inbox-handle/v1"

    fun windowIndex(nowMillis: Long): Long = Math.floorDiv(nowMillis, WINDOW_MILLIS)

    /**
     * Windows this device should be attached for. Two slots cover normal clock skew between two
     * phones without shortening the rotation period.
     */
    fun windowsFor(nowMillis: Long, slots: Int): List<Long> =
        (0 until slots.coerceAtLeast(1)).map { windowIndex(nowMillis) + it }

    fun inboxHandle(publicKeyBase64: String, window: Long): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(DOMAIN.toByteArray(Charsets.UTF_8))
        digest.update(PeerIds.digest(publicKeyBase64).toByteArray(Charsets.UTF_8))
        digest.update(window.toString().toByteArray(Charsets.UTF_8))
        return digest.digest()
    }
}
