package dev.arya.messenger.data

import android.content.Context
import dev.arya.messenger.network.relay.RelayNode
import dev.arya.messenger.security.PeerIds

/**
 * User-owned network configuration.
 *
 * Deliberately contains no hardcoded relay, bootstrap, or rendezvous endpoint: the app must work
 * with zero infrastructure and zero operating cost. Anything listed here was entered by the user or
 * came from a contact's invitation, so there is nothing to pay for and nothing to shut down.
 */
class NetworkConfig(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Ordered list of relay endpoints, tried in the order the user defined them. */
    var relayEndpoints: List<String>
        get() = prefs.getString(KEY_RELAYS, "").orEmpty()
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        set(value) {
            prefs.edit().putString(KEY_RELAYS, value.map { it.trim() }.filter { it.isNotEmpty() }.joinToString(",")).apply()
        }

    /**
     * Address other devices can dial to reach this one directly, e.g. "203.0.113.5:45871".
     * Empty means "not reachable directly", which is the normal case on mobile data.
     */
    var publicEndpoint: String
        get() = prefs.getString(KEY_ENDPOINT, "").orEmpty()
        set(value) {
            prefs.edit().putString(KEY_ENDPOINT, value.trim()).apply()
        }

    /** Opt-in: help other users by relaying their sessions. Uses upload bandwidth. */
    var nodeModeEnabled: Boolean
        get() = prefs.getBoolean(KEY_NODE_MODE, false)
        set(value) {
            prefs.edit().putBoolean(KEY_NODE_MODE, value).apply()
        }

    var nodePort: Int
        get() = prefs.getInt(KEY_NODE_PORT, RelayNode.DEFAULT_PORT)
        set(value) {
            prefs.edit().putInt(KEY_NODE_PORT, value).apply()
        }

    /** Reachability hints a contact shared in their QR invitation. */
    data class PeerHint(val endpoint: String? = null, val relay: String? = null)

    fun peerHint(publicKey: String): PeerHint? {
        val key = runCatching { PeerIds.digest(publicKey) }.getOrNull() ?: return null
        val raw = prefs.getString(HINT_PREFIX + key, null) ?: return null
        val parts = raw.split('|')
        val endpoint = parts.getOrNull(0)?.takeIf { it.isNotBlank() }
        val relay = parts.getOrNull(1)?.takeIf { it.isNotBlank() }
        return if (endpoint == null && relay == null) null else PeerHint(endpoint, relay)
    }

    fun setPeerHint(publicKey: String, endpoint: String?, relay: String?) {
        val key = runCatching { PeerIds.digest(publicKey) }.getOrNull() ?: return
        if (endpoint.isNullOrBlank() && relay.isNullOrBlank()) {
            prefs.edit().remove(HINT_PREFIX + key).apply()
            return
        }
        prefs.edit().putString(HINT_PREFIX + key, "${endpoint.orEmpty()}|${relay.orEmpty()}").apply()
    }

    private companion object {
        const val PREFS = "arya_network"
        const val KEY_RELAYS = "relay_endpoints"
        const val KEY_ENDPOINT = "public_endpoint"
        const val KEY_NODE_MODE = "node_mode"
        const val KEY_NODE_PORT = "node_port"
        const val HINT_PREFIX = "hint_"
    }
}
