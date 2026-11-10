package dev.arya.messenger.network.relay

import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Pairing protocol between an Arya client and a relay node.
 *
 * The node only splices two sockets together. It never sees message plaintext, because the two
 * peers immediately run their own authenticated session over the resulting byte pipe.
 *
 * The only identifier exchanged with the node is a rotating window handle
 * ([dev.arya.messenger.security.RendezvousToken]), never a permanent peer id. The node can see that
 * *someone* is attached to a handle at a given moment; it cannot tell who, cannot link a handle to
 * an identity, and cannot link handles across rotation boundaries.
 */
object RelayProtocol {

    const val MAGIC = 0x41525952 // "ARYR"
    const val VERSION = 1
    const val TOKEN_BYTES = 32

    enum class Role { DELIVER, INBOX }

    enum class Status(val code: Int) {
        /** You arrived first; you are the initiator of the session. */
        PAIRED_INITIATOR(0),

        /** A sender is connected; you are the responder of the session. */
        PAIRED_RESPONDER(1),

        /** Nobody is attached to that handle right now. */
        PEER_OFFLINE(2),

        /** The node refused or gave up on this pairing. */
        REJECTED(3)
    }

    data class Hello(val role: Role, val token: ByteArray)

    data class HostPort(val host: String, val port: Int)

    /** Accepts "host:port" and "tcp://host:port"; rejects anything else rather than guessing. */
    fun parseEndpoint(raw: String): HostPort? {
        val trimmed = raw.trim().removePrefix("tcp://").removePrefix("TCP://")
        if (trimmed.isBlank() || trimmed.any { it.isWhitespace() }) return null
        val separator = trimmed.lastIndexOf(':')
        if (separator <= 0 || separator == trimmed.lastIndex) return null
        val host = trimmed.substring(0, separator)
        val port = trimmed.substring(separator + 1).toIntOrNull() ?: return null
        if (port !in 1..65535) return null
        return HostPort(host, port)
    }

    fun writeHello(out: DataOutputStream, role: Role, token: ByteArray) {
        require(token.size == TOKEN_BYTES) { "Rendezvous handle must be $TOKEN_BYTES bytes" }
        out.writeInt(MAGIC)
        out.writeInt(VERSION)
        out.writeByte(if (role == Role.DELIVER) 0 else 1)
        out.write(token)
        out.flush()
    }

    fun readHello(input: DataInputStream): Hello {
        require(input.readInt() == MAGIC) { "Not an Arya relay" }
        require(input.readInt() == VERSION) { "Unsupported relay version" }
        val role = if (input.readUnsignedByte() == 0) Role.DELIVER else Role.INBOX
        val token = ByteArray(TOKEN_BYTES).also(input::readFully)
        return Hello(role, token)
    }

    fun writeStatus(out: DataOutputStream, status: Status) {
        out.writeByte(status.code)
        out.flush()
    }

    fun readStatus(input: DataInputStream): Status {
        val code = input.readUnsignedByte()
        return Status.values().firstOrNull { it.code == code }
            ?: throw IllegalArgumentException("Unknown relay status $code")
    }

    internal fun keyOf(token: ByteArray): String =
        token.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}
