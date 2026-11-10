package dev.arya.messenger.network

import dev.arya.messenger.data.LanFailure
import dev.arya.messenger.data.LanTransportException
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/**
 * Payloads that reach a text-framed transport must be valid UTF-8; anything else is reported as a
 * payload problem instead of being silently mangled by charset replacement.
 */
internal fun decodeUtf8(payload: ByteArray): String? = runCatching {
    Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(payload))
        .toString()
}.getOrNull()

internal fun classifyFailure(error: Throwable?): FailureReason = when (error) {
    is LanTransportException -> when (error.failure) {
        LanFailure.UNTRUSTED_PEER -> FailureReason.UNTRUSTED_PEER
        LanFailure.AUTH_FAILED -> FailureReason.AUTH_FAILED
        LanFailure.PAYLOAD_INVALID -> FailureReason.PAYLOAD_INVALID
        LanFailure.PROTOCOL -> FailureReason.UNREACHABLE
    }
    is SocketTimeoutException -> FailureReason.TIMEOUT
    else -> FailureReason.UNREACHABLE
}
