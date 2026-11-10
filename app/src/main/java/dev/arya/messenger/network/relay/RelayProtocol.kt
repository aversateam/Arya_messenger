package dev.arya.messenger.network.relay

import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Pairing protocol between an Arya client and a relay node.
 *
 * The node only splices two sockets together. It never sees message plaintext, because the two
 * peers immediately run their own authenticated, encrypted session over the resulting byte pipe.
 * What the node can observe is metadata: which peer ids connect, when, and from which address.
 */
object RelayProtocol {

    const val MAGIC = 0x41525952 // "ARYR"
    const val VERSION = 1
    const val ID_BYTES = 32

    enum class Role { DELIVER, INBOX }

    enum class Status(val code: Int) {
        /** You arrived first; you are the initiator of the session. */
        PAIRED_INITIATOR(0),

        /** A sender is connected; you are the responder of the session. */
        PAIRED_RESPONDER(1),

        /** The recipient is not attached to this relay right now. */
        PEER_OFFLINE(2),

        /** The node refused or gave up on this pairing. */
        REJECTED(3)
    }

    data class Hello(val role: Role, val selfId: ByteArray, val targetId: ByteArray)

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

    fun writeHello(out: DataOutputStream, role: Role, selfId: ByteArray, targetId: ByteArray) {
        require(selfId.size == ID_BYTES) { "Peer id must be 32 bytes" }
        require(targetId.size == ID_BYTES) { "Target id must be 32 bytes" }
        out.writeInt(MAGIC)
        out.writeInt(VERSION)
        out.writeByte(if (role == Role.DELIVER) 0 else 1)
        out.write(selfId)
        out.write(targetId)
        out.flush()
    }

    fun readHello(input: DataInputStream): Hello {
        require(input.readInt() == MAGIC) { "Not an Arya relay" }
        require(input.readInt() == VERSION) { "Unsupported relay version" }
        val role = if (input.readUnsignedByte() == 0) Role.DELIVER else Role.INBOX
        val selfId = ByteArray(ID_BYTES).also(input::readFully)
        val targetId = ByteArray(ID_BYTES).also(input::readFully)
        return Hello(role, selfId, targetId)
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

    internal fun keyOf(id: ByteArray): String =
        id.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}
