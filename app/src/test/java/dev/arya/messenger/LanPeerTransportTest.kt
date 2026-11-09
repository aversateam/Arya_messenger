package dev.arya.messenger

import dev.arya.messenger.data.LanPeerTransport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class LanPeerTransportTest {
    @Test fun tcpMessageIsPersistCallbackedBeforeAck() = runBlocking {
        val received = CountDownLatch(1)
        val payload = AtomicReference<String>()
        val transport = LanPeerTransport { _, peerName, body ->
            payload.set("$peerName:$body")
            received.countDown()
        }
        try {
            transport.start()
            // Allow the listener thread to bind before the loopback client connects.
            Thread.sleep(150)
            val result = transport.send("127.0.0.1", "Test peer", "public-key-test", "hello over tcp")
            assertTrue("TCP send should receive receiver ACK", result.isSuccess)
            assertTrue("Receiver callback should run", received.await(1, TimeUnit.SECONDS))
            assertEquals("Test peer:hello over tcp", payload.get())
        } finally {
            transport.close()
        }
    }
}
