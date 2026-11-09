package dev.arya.messenger

import dev.arya.messenger.security.IdentityQr
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Base64

class IdentityQrTest {
    @Test fun fingerprintIsStableAndFormattedAsHexGroups() {
        val key = Base64.getEncoder().encodeToString(ByteArray(91) { it.toByte() })
        val first = IdentityQr.fingerprint(key)
        val second = IdentityQr.fingerprint(key)
        assertEquals(first, second)
        assertEquals(16, first.split(" ").size)
        assertEquals(4, first.split(" ").first().length)
    }
}
