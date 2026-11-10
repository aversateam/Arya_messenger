package dev.arya.messenger.network

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Route selection and observable network state.
 *
 * Discovery is deliberately absent from this contract: learning that a peer exists says nothing
 * about being able to reach it, so only [DeliveryOutcome.Delivered] counts as success.
 */
interface NetworkRuntime {
    val state: StateFlow<NetworkState>

    fun start(displayName: String)
    fun stop()

    /** Exposes a registered route so concrete screens can use route-specific affordances. */
    fun transport(kind: RouteKind): PeerTransport?

    suspend fun send(target: PeerRef, payload: ByteArray): DeliveryOutcome
}

/**
 * Tries routes in registration order and escalates only on failure.
 *
 * Backoff rule: a route that just failed for this peer is skipped for [backoffMillis], except when
 * it is the cheapest currently available route. The cheapest route is always attempted so an
 * explicit user action is never silently swallowed, while expensive escalation attempts (relay,
 * hole punching) are not repeated on every keystroke.
 */
class DefaultNetworkRuntime(
    private val transports: List<PeerTransport>,
    private val backoffMillis: Long = DEFAULT_BACKOFF_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis
) : NetworkRuntime {

    private val _state = MutableStateFlow<NetworkState>(NetworkState.Stopped)
    override val state: StateFlow<NetworkState> = _state.asStateFlow()

    private val failures = ConcurrentHashMap<String, Long>()
    private val running = AtomicBoolean(false)

    init {
        require(transports.isNotEmpty()) { "At least one transport must be registered" }
        require(transports.map { it.kind }.distinct().size == transports.size) { "Duplicate route kind" }
    }

    override fun start(displayName: String) {
        if (!running.compareAndSet(false, true)) return
        _state.value = NetworkState.Starting
        transports.forEach { runCatching { it.start(displayName) } }
        refresh()
    }

    override fun stop() {
        if (!running.compareAndSet(true, false)) return
        transports.forEach { runCatching { it.stop() } }
        failures.clear()
        _state.value = NetworkState.Stopped
    }

    override fun transport(kind: RouteKind): PeerTransport? = transports.firstOrNull { it.kind == kind }

    override suspend fun send(target: PeerRef, payload: ByteArray): DeliveryOutcome {
        if (payload.isEmpty()) return DeliveryOutcome.Failed(null, FailureReason.PAYLOAD_INVALID)
        if (!running.get()) return DeliveryOutcome.Failed(null, FailureReason.TRANSPORT_UNAVAILABLE)

        val candidates = transports.filter { it.available.value }
        if (candidates.isEmpty()) return DeliveryOutcome.Failed(null, FailureReason.TRANSPORT_UNAVAILABLE)

        var lastFailure: DeliveryOutcome.Failed? = null
        candidates.forEachIndexed { index, transport ->
            val key = backoffKey(target, transport.kind)
            val failedAt = failures[key]
            val coolingDown = failedAt != null && clock() - failedAt < backoffMillis
            if (index > 0 && coolingDown) return@forEachIndexed

            when (val outcome = transport.send(target, payload)) {
                is DeliveryOutcome.Delivered -> {
                    failures.remove(key)
                    return outcome
                }
                is DeliveryOutcome.Queued -> return outcome
                is DeliveryOutcome.Failed -> {
                    failures[key] = clock()
                    lastFailure = outcome
                }
            }
        }
        return lastFailure ?: DeliveryOutcome.Failed(null, FailureReason.TRANSPORT_UNAVAILABLE)
    }

    private fun refresh() {
        if (!running.get()) return
        val active = transports.filter { it.available.value }.map { it.kind }.toSet()
        _state.value = if (active.isEmpty()) {
            NetworkState.Degraded("هیچ مسیر ارتباطی فعال نیست")
        } else {
            NetworkState.Running(Reachability.UNKNOWN, active)
        }
    }

    /** Falls back to the address list when the peer identity is not known yet (LAN dialing). */
    private fun backoffKey(target: PeerRef, route: RouteKind): String {
        val identity = target.publicKey.trim().ifBlank { target.addresses.joinToString(",") }
        return "$identity|$route"
    }

    companion object {
        const val DEFAULT_BACKOFF_MILLIS = 15_000L
    }
}
