package com.freerdp.feature.telemetry

import com.freerdp.feature.telemetry.network.FastPathPduPrioritizer
import com.freerdp.feature.telemetry.network.LowLatencySocketConfig
import io.mockk.mockk
import io.mockk.verifyOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.SocketChannel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class LowLatencySocketConfigTest {

    @Test
    fun testSocketConfigurationParameters() {
        val server = ServerSocket(0)
        val port = server.localPort
        val client = Socket("127.0.0.1", port)
        val accepted = server.accept()

        try {
            val result = LowLatencySocketConfig.configureSocket(client)
            assertTrue("Socket configuration must succeed", result.isSuccess)

            assertTrue("TCP_NODELAY must be true", client.tcpNoDelay)
            assertEquals(LowLatencySocketConfig.DEFAULT_RX_BUFFER_BYTES, client.receiveBufferSize)
            assertEquals(LowLatencySocketConfig.DEFAULT_TX_BUFFER_BYTES, client.sendBufferSize)
            assertTrue("SO_KEEPALIVE must be true", client.keepAlive)
            // IP_TOS readback is platform-dependent (Windows JDK always returns 0),
            // so bound-check the readback instead of requiring an exact round-trip.
            val trafficClass = client.trafficClass
            assertTrue(
                "trafficClass must be within the 8-bit TOS range, was $trafficClass",
                trafficClass in LowLatencySocketConfig.MIN_IP_TOS..LowLatencySocketConfig.MAX_IP_TOS
            )

            val inspection = LowLatencySocketConfig.inspectSocket(client)
            assertEquals(true, inspection["tcpNoDelay"])
            assertEquals(LowLatencySocketConfig.DEFAULT_RX_BUFFER_BYTES, inspection["receiveBufferSize"])
        } finally {
            client.close()
            accepted.close()
            server.close()
        }
    }

    /**
     * Regression guard: every tuning option — including IP_TOS, whose OS readback is
     * not portable — must actually be APPLIED to the socket by the config layer.
     * Verified with a strict mock so the assertion works on platforms where the OS
     * cannot round-trip IP_TOS (Windows JDK returns 0 from getTrafficClass).
     */
    @Test
    fun testAllTuningOptionsAreAppliedInOrder() {
        val socket = mockk<Socket>(relaxed = true)

        val result = LowLatencySocketConfig.configureSocket(socket)
        assertTrue("Configuration against a live socket must succeed", result.isSuccess)

        verifyOrder {
            socket.tcpNoDelay = LowLatencySocketConfig.DEFAULT_TCP_NODELAY
            socket.receiveBufferSize = LowLatencySocketConfig.DEFAULT_RX_BUFFER_BYTES
            socket.sendBufferSize = LowLatencySocketConfig.DEFAULT_TX_BUFFER_BYTES
            socket.trafficClass = LowLatencySocketConfig.IPTOS_LOWDELAY
            socket.keepAlive = LowLatencySocketConfig.DEFAULT_KEEPALIVE
        }
    }

    // ---- TuningOptions range validation boundaries (F21, Tier-2 style) ----

    @Test
    fun testRxBufferLowerBoundaryValidated() {
        // One below the minimum is rejected; the exact minimum is accepted.
        assertThrows(IllegalArgumentException::class.java) {
            LowLatencySocketConfig.TuningOptions(rxBufferBytes = LowLatencySocketConfig.MIN_BUFFER_BYTES - 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            LowLatencySocketConfig.TuningOptions(rxBufferBytes = -1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            LowLatencySocketConfig.TuningOptions(rxBufferBytes = 0)
        }
        val minValid = LowLatencySocketConfig.TuningOptions(rxBufferBytes = LowLatencySocketConfig.MIN_BUFFER_BYTES)
        assertEquals(LowLatencySocketConfig.MIN_BUFFER_BYTES, minValid.rxBufferBytes)
    }

    @Test
    fun testRxBufferUpperBoundaryValidated() {
        val maxValid = LowLatencySocketConfig.TuningOptions(rxBufferBytes = LowLatencySocketConfig.MAX_BUFFER_BYTES)
        assertEquals(LowLatencySocketConfig.MAX_BUFFER_BYTES, maxValid.rxBufferBytes)
        assertThrows(IllegalArgumentException::class.java) {
            LowLatencySocketConfig.TuningOptions(rxBufferBytes = LowLatencySocketConfig.MAX_BUFFER_BYTES + 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            LowLatencySocketConfig.TuningOptions(rxBufferBytes = Int.MAX_VALUE)
        }
    }

    @Test
    fun testTxBufferBoundariesValidated() {
        assertThrows(IllegalArgumentException::class.java) {
            LowLatencySocketConfig.TuningOptions(txBufferBytes = LowLatencySocketConfig.MIN_BUFFER_BYTES - 1)
        }
        val maxValid = LowLatencySocketConfig.TuningOptions(txBufferBytes = LowLatencySocketConfig.MAX_BUFFER_BYTES)
        assertEquals(LowLatencySocketConfig.MAX_BUFFER_BYTES, maxValid.txBufferBytes)
        assertThrows(IllegalArgumentException::class.java) {
            LowLatencySocketConfig.TuningOptions(txBufferBytes = LowLatencySocketConfig.MAX_BUFFER_BYTES + 1)
        }
    }

    @Test
    fun testIpTosBoundariesValidated() {
        // Port-0/65535 equivalent for the 8-bit TOS field.
        assertThrows(IllegalArgumentException::class.java) {
            LowLatencySocketConfig.TuningOptions(ipTos = LowLatencySocketConfig.MIN_IP_TOS - 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            LowLatencySocketConfig.TuningOptions(ipTos = LowLatencySocketConfig.MAX_IP_TOS + 1)
        }
        assertEquals(0, LowLatencySocketConfig.TuningOptions(ipTos = 0).ipTos)
        assertEquals(255, LowLatencySocketConfig.TuningOptions(ipTos = 255).ipTos)
        assertEquals(LowLatencySocketConfig.IPTOS_LOWDELAY, LowLatencySocketConfig.TuningOptions().ipTos)
    }

    @Test
    fun testConfigureClosedSocketFailsLoudly() {
        val server = ServerSocket(0)
        val client = Socket("127.0.0.1", server.localPort)
        val accepted = server.accept()
        client.close()
        try {
            val result = LowLatencySocketConfig.configureSocket(client)
            assertTrue("Closed socket must produce a failed Result", result.isFailure)
            assertTrue(
                "Failure must be caused by the closed-socket check",
                result.exceptionOrNull() is IllegalStateException
            )
        } finally {
            accepted.close()
            server.close()
        }
    }

    @Test
    fun testChannelConfigurationAndClosedChannelFailure() {
        val channel = SocketChannel.open()
        try {
            val result = LowLatencySocketConfig.configureChannel(channel)
            assertTrue("Open channel configuration must succeed", result.isSuccess)
            assertTrue(channel.getOption(java.net.StandardSocketOptions.TCP_NODELAY))
            assertEquals(
                LowLatencySocketConfig.DEFAULT_RX_BUFFER_BYTES,
                channel.getOption(java.net.StandardSocketOptions.SO_RCVBUF)
            )
            assertEquals(
                LowLatencySocketConfig.DEFAULT_TX_BUFFER_BYTES,
                channel.getOption(java.net.StandardSocketOptions.SO_SNDBUF)
            )
            assertTrue(channel.getOption(java.net.StandardSocketOptions.SO_KEEPALIVE))
        } finally {
            channel.close()
        }

        val closed = SocketChannel.open()
        closed.close()
        val result = LowLatencySocketConfig.configureChannel(closed)
        assertTrue("Closed channel must produce a failed Result", result.isFailure)
    }

    @Test
    fun testValidateRejectsOutOfRangeOptions() {
        assertThrows(IllegalArgumentException::class.java) {
            LowLatencySocketConfig.validate(
                LowLatencySocketConfig.TuningOptions().copy(rxBufferBytes = 1)
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            LowLatencySocketConfig.validate(
                LowLatencySocketConfig.TuningOptions().copy(txBufferBytes = -1024)
            )
        }
        val valid = LowLatencySocketConfig.validate(LowLatencySocketConfig.TuningOptions())
        assertEquals(LowLatencySocketConfig.DEFAULT_RX_BUFFER_BYTES, valid.rxBufferBytes)
    }

    // ---- FastPath PDU detection / classification / prioritization ----

    @Test
    fun testFastPathPduDetectionAndClassification() {
        // FastPath header has lowest 2 bits as 0x00
        val fastPathInputByte: Byte = 0x00
        val fastPathOutputByte: Byte = 0x04 // (0x04 & 0x03) == 0x00
        val slowPathTpktByte: Byte = 0x03

        assertTrue(FastPathPduPrioritizer.isFastPath(fastPathInputByte))
        assertTrue(FastPathPduPrioritizer.isFastPath(fastPathOutputByte))
        assertFalse(FastPathPduPrioritizer.isFastPath(slowPathTpktByte))

        val inputPayload = byteArrayOf(0x00, 0x10, 0x20)
        val outputPayload = byteArrayOf(0x04, 0x10, 0x20)
        val tpktPayload = byteArrayOf(0x03, 0x00, 0x00, 0x13)
        val bulkPayload = byteArrayOf(0x01, 0x02, 0x03)

        assertEquals(
            FastPathPduPrioritizer.PduPriority.FASTPATH_INPUT,
            FastPathPduPrioritizer.classifyPdu(inputPayload, isOutbound = true)
        )
        assertEquals(
            FastPathPduPrioritizer.PduPriority.FASTPATH_OUTPUT,
            FastPathPduPrioritizer.classifyPdu(outputPayload, isOutbound = false)
        )
        assertEquals(
            FastPathPduPrioritizer.PduPriority.SLOWPATH_INTERACTIVE,
            FastPathPduPrioritizer.classifyPdu(tpktPayload)
        )
        assertEquals(
            FastPathPduPrioritizer.PduPriority.BULK_DATA,
            FastPathPduPrioritizer.classifyPdu(bulkPayload)
        )
    }

    @Test
    fun testEmptyPayloadClassificationBoundary() {
        assertEquals(
            FastPathPduPrioritizer.PduPriority.BULK_DATA,
            FastPathPduPrioritizer.classifyPdu(ByteArray(0))
        )
    }

    @Test
    fun testPduPriorityQueueDispatchesFastPathFirst() {
        val queue = FastPathPduPrioritizer.PduQueue()

        val bulkData = byteArrayOf(0x01, 0x02)
        val slowpath = byteArrayOf(0x03, 0x00, 0x01)
        val fastpathInput = byteArrayOf(0x00, 0x55)

        // Enqueue in reverse order of priority: bulk first, then slowpath, then fastpath
        queue.enqueue(bulkData)
        queue.enqueue(slowpath)
        queue.enqueue(fastpathInput, isOutbound = true)

        assertEquals(3, queue.size)

        // Dequeue should return FASTPATH_INPUT first, then SLOWPATH, then BULK
        val first = queue.poll()
        val second = queue.poll()
        val third = queue.poll()

        assertEquals(FastPathPduPrioritizer.PduPriority.FASTPATH_INPUT, first?.priority)
        assertEquals(FastPathPduPrioritizer.PduPriority.SLOWPATH_INTERACTIVE, second?.priority)
        assertEquals(FastPathPduPrioritizer.PduPriority.BULK_DATA, third?.priority)
    }

    @Test
    fun testPduQueueStrictFourLevelPriorityAndFifoWithinClass() {
        val queue = FastPathPduPrioritizer.PduQueue()

        val bulk1 = queue.enqueue(byteArrayOf(0x01, 0x01))!!
        val bulk2 = queue.enqueue(byteArrayOf(0x01, 0x02))!!
        val slow = queue.enqueue(byteArrayOf(0x03, 0x00))!!
        val output = queue.enqueue(byteArrayOf(0x04, 0x00), isOutbound = false)!!
        val input = queue.enqueue(byteArrayOf(0x00, 0x08), isOutbound = true)!!

        assertEquals(input, queue.poll())
        assertEquals(output, queue.poll())
        assertEquals(slow, queue.poll())
        // FIFO within BULK class
        assertEquals(bulk1, queue.poll())
        assertEquals(bulk2, queue.poll())
        assertNull(queue.poll())
        assertTrue(queue.isEmpty)
    }

    /**
     * Starvation prevention with an injected clock: bulk (clipboard) PDUs must win
     * ahead of strict priority once they have waited past the threshold — while strict
     * priority still holds below the threshold.
     */
    @Test
    fun testBulkStarvationPreventionAfterThreshold() {
        var nowNanos = 0L
        val threshold = 500_000_000L
        val queue = FastPathPduPrioritizer.PduQueue(
            capacity = 64,
            starvationThresholdNanos = threshold,
            nanoClock = { nowNanos }
        )

        // Bulk PDU queued at t=0.
        queue.enqueue(byteArrayOf(0x01, 0x00))

        // Below the threshold: input always wins, bulk stays queued.
        for (i in 1..4) {
            nowNanos = i * 100_000_000L // 100ms .. 400ms
            queue.enqueue(byteArrayOf(0x00, i.toByte()), isOutbound = true)
            val pdu = queue.poll()
            assertEquals(
                "Below threshold strict priority must hold",
                FastPathPduPrioritizer.PduPriority.FASTPATH_INPUT,
                pdu?.priority
            )
        }
        assertEquals("Bulk must still be waiting below threshold", 1, queue.size)

        // At/after the threshold: the starved bulk PDU is promoted ahead of input.
        nowNanos = threshold
        queue.enqueue(byteArrayOf(0x00, 0x63), isOutbound = true)
        assertEquals(
            "Starved bulk must be served first",
            FastPathPduPrioritizer.PduPriority.BULK_DATA,
            queue.poll()?.priority
        )
        assertEquals(
            FastPathPduPrioritizer.PduPriority.FASTPATH_INPUT,
            queue.poll()?.priority
        )
    }

    @Test
    fun testPduQueueCapacityShedsBulkBeforeInput() {
        val queue = FastPathPduPrioritizer.PduQueue(capacity = 4)

        val bulkA = queue.enqueue(byteArrayOf(0x01, 0x01))!!
        queue.enqueue(byteArrayOf(0x01, 0x02))
        queue.enqueue(byteArrayOf(0x00, 0x01), isOutbound = true)
        queue.enqueue(byteArrayOf(0x00, 0x02), isOutbound = true)
        assertEquals(4, queue.size)

        // Queue full: another input evicts the oldest bulk instead of being rejected.
        val input3 = queue.enqueue(byteArrayOf(0x00, 0x03), isOutbound = true)
        assertNotNull(input3)
        assertEquals(4, queue.size)
        assertEquals(1L, queue.droppedCount)

        // Now drain inputs and refill with inputs until full-of-input; bulk must be
        // rejected (bounded queue, input never dropped).
        queue.poll(); queue.poll(); queue.poll(); queue.poll()
        assertEquals(0, queue.size)
        queue.enqueue(byteArrayOf(0x00, 0x11), isOutbound = true)
        queue.enqueue(byteArrayOf(0x00, 0x12), isOutbound = true)
        queue.enqueue(byteArrayOf(0x00, 0x13), isOutbound = true)
        queue.enqueue(byteArrayOf(0x00, 0x14), isOutbound = true)
        assertEquals(4, queue.size)

        val rejectedBulk = queue.enqueue(byteArrayOf(0x01, 0x77))
        assertNull("Bulk into an input-only full queue must be rejected", rejectedBulk)
        assertEquals(4, queue.size)
        assertEquals(2L, queue.droppedCount)

        // Evicted-first bulk (bulkA) is not resurrected anywhere in the queue.
        val drained = generateSequence { queue.poll() }.toList()
        assertFalse(drained.any { it == bulkA })
    }

    @Test
    fun testPduQueueBoundaryValidation() {
        assertThrows(IllegalArgumentException::class.java) {
            FastPathPduPrioritizer.PduQueue(capacity = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            FastPathPduPrioritizer.PduQueue(capacity = -1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            FastPathPduPrioritizer.PduQueue(starvationThresholdNanos = 0L)
        }
        // Minimal valid boundary works.
        val queue = FastPathPduPrioritizer.PduQueue(capacity = 1)
        assertNotNull(queue.enqueue(byteArrayOf(0x00, 0x01), isOutbound = true))
        assertNull("Second PDU into capacity-1 input queue must be rejected", queue.enqueue(byteArrayOf(0x00, 0x02), isOutbound = true))
        assertEquals(1, queue.size)
    }

    @Test
    fun testPduQueueConcurrentProducersPreserveAccounting() {
        val queue = FastPathPduPrioritizer.PduQueue(capacity = 10_000)
        val producers = 4
        val perProducer = 250
        val pool = Executors.newFixedThreadPool(producers + 1)
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(producers)

        repeat(producers) { p ->
            pool.submit {
                startLatch.await()
                repeat(perProducer) { i ->
                    val isInput = (i % 4 == 0)
                    queue.enqueue(
                        byteArrayOf(if (isInput) 0x00 else 0x01, i.toByte()),
                        isOutbound = isInput
                    )
                }
                doneLatch.countDown()
            }
        }

        var drained = 0
        pool.submit {
            startLatch.await()
            while (doneLatch.count > 0 || queue.size > 0) {
                while (queue.poll() != null) drained++
            }
            while (queue.poll() != null) drained++
        }

        startLatch.countDown()
        assertTrue(doneLatch.await(10, TimeUnit.SECONDS))
        pool.shutdown()
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))

        // Drain any remainder on this thread.
        var remainder = queue.poll()
        while (remainder != null) {
            drained++
            remainder = queue.poll()
        }

        val total = producers * perProducer
        assertEquals(
            "Every produced PDU must be drained or explicitly shed",
            0,
            total - drained - queue.droppedCount.toInt()
        )
        assertEquals(0, queue.size)
        assertEquals("No capacity drops expected (capacity > total)", 0L, queue.droppedCount)
    }
}
