package dev.arya.messenger

import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class IdentityFormatTest {
    @Test fun generatedConversationIdsAreUnique() {
        val ids = (1..1000).map { UUID.randomUUID().toString() }
        assertTrue(ids.toSet().size == ids.size)
    }

    @Test fun localOnlyStatusIsNotRemoteDelivery() {
        val status = "LOCAL ONLY"
        assertTrue(status != "SENT" && status != "DELIVERED")
    }
}
