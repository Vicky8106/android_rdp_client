package com.freerdp.feature.telemetry.network

/**
 * FastPath PDU detection, classification, and priority dispatching.
 *
 * FastPath packets bypass standard slow-path TPKT/X.224/MCS protocol stack headers,
 * reducing transmission overhead and latency. Input events and display updates are
 * prioritized over control channel and bulk data transfers.
 *
 * Starvation prevention: a strictly-priority queue would never dequeue clipboard/bulk
 * PDUs during continuous input traffic. [PduQueue] therefore promotes the oldest bulk
 * PDU once it has waited at least `starvationThresholdNanos`, bounding bulk latency
 * while preserving strict priority underneath that bound.
 */
object FastPathPduPrioritizer {

    // FastPath header bitmasks (MS-RDPBCGR Section 2.2.9.1.2)
    const val FASTPATH_ACTION_FASTPATH: Int = 0x0
    const val FASTPATH_ACTION_X224: Int = 0x3
    const val FASTPATH_ACTION_MASK: Int = 0x03

    const val FASTPATH_INPUT_EVENT_SCANCODE: Int = 0x0
    const val FASTPATH_INPUT_EVENT_MOUSE: Int = 0x1
    const val FASTPATH_INPUT_EVENT_MOUSEX: Int = 0x2
    const val FASTPATH_INPUT_EVENT_SYNC: Int = 0x3
    const val FASTPATH_INPUT_EVENT_UNICODE: Int = 0x4

    enum class PduPriority(val rank: Int) {
        FASTPATH_INPUT(1),       // User interactions: highest priority
        FASTPATH_OUTPUT(2),      // Remote screen updates: high priority
        SLOWPATH_INTERACTIVE(3), // Control messages (keepalive, sync): medium priority
        BULK_DATA(4)             // Clipboard, audio, file redirection: lowest priority
    }

    data class PrioritizedPdu(
        val sequenceId: Long,
        val priority: PduPriority,
        val payload: ByteArray,
        val timestampNanos: Long = System.nanoTime()
    ) : Comparable<PrioritizedPdu> {
        override fun compareTo(other: PrioritizedPdu): Int {
            val pComp = this.priority.rank.compareTo(other.priority.rank)
            return if (pComp != 0) pComp else this.sequenceId.compareTo(other.sequenceId)
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is PrioritizedPdu) return false
            return sequenceId == other.sequenceId && priority == other.priority && payload.contentEquals(other.payload)
        }

        override fun hashCode(): Int {
            var result = sequenceId.hashCode()
            result = 31 * result + priority.hashCode()
            result = 31 * result + payload.contentHashCode()
            return result
        }
    }

    /**
     * Determines whether the given header byte denotes a FastPath PDU.
     * In FastPath, the lowest 2 bits of the first byte are 0x00.
     */
    fun isFastPath(firstHeaderByte: Byte): Boolean {
        return (firstHeaderByte.toInt() and FASTPATH_ACTION_MASK) == FASTPATH_ACTION_FASTPATH
    }

    /**
     * Classifies a PDU payload by examining its header.
     */
    fun classifyPdu(payload: ByteArray, isOutbound: Boolean = false): PduPriority {
        if (payload.isEmpty()) return PduPriority.BULK_DATA
        val firstByte = payload[0]
        return if (isFastPath(firstByte)) {
            if (isOutbound) PduPriority.FASTPATH_INPUT else PduPriority.FASTPATH_OUTPUT
        } else {
            // Slowpath: check if TPKT (0x03)
            if ((firstByte.toInt() and 0xFF) == 0x03) {
                PduPriority.SLOWPATH_INTERACTIVE
            } else {
                PduPriority.BULK_DATA
            }
        }
    }

    /**
     * Bounded, thread-safe priority queue for ordering outbound and inbound PDUs.
     *
     * Behavior contract:
     * - Strict priority order: FASTPATH_INPUT > FASTPATH_OUTPUT > SLOWPATH_INTERACTIVE > BULK_DATA,
     *   FIFO within each priority class (sequence id order).
     * - Starvation prevention: when the oldest BULK PDU has waited >= [starvationThresholdNanos]
     *   (measured against [nanoClock]), it is served ahead of higher priorities on the next poll.
     * - Bounded memory: at [capacity] entries the queue sheds from the LOWEST priority class that
     *   ranks strictly below the incoming PDU (oldest first: bulk, then slow-path, then output).
     *   If no such class has entries, the incoming PDU is rejected (returned as `null`) so the
     *   queue can never grow unbounded — fastpath input is never evicted to make room.
     *
     * @param capacity maximum queued PDUs (must be > 0)
     * @param starvationThresholdNanos wait time after which bulk PDUs bypass strict priority
     * @param nanoClock monotonic nanosecond clock, injectable for deterministic tests
     */
    class PduQueue(
        val capacity: Int = 1024,
        val starvationThresholdNanos: Long = DEFAULT_STARVATION_THRESHOLD_NANOS,
        private val nanoClock: () -> Long = System::nanoTime
    ) {
        init {
            require(capacity > 0) { "capacity must be > 0, got $capacity" }
            require(starvationThresholdNanos > 0L) { "starvationThresholdNanos must be > 0, got $starvationThresholdNanos" }
        }

        // One FIFO deque per PduPriority.rank (index rank - 1), guarded by [lock].
        private val queues: Array<ArrayDeque<PrioritizedPdu>> =
            Array(PduPriority.entries.size) { ArrayDeque() }
        private val lock = Any()
        private var seqCounter = 0L
        private var shedOrRejectedTotal = 0L

        /** Number of PDUs rejected or shed due to [capacity] bound. */
        val droppedCount: Long get() = synchronized(lock) { shedOrRejectedTotal }

        private fun queueFor(priority: PduPriority): ArrayDeque<PrioritizedPdu> = queues[priority.rank - 1]

        /**
         * Enqueues a classified PDU. Returns the stored PDU, or `null` when it was
         * rejected because the queue is full of equal/higher priority traffic.
         */
        fun enqueue(payload: ByteArray, isOutbound: Boolean = false): PrioritizedPdu? {
            val priority = classifyPdu(payload, isOutbound)
            return synchronized(lock) {
                var admitted = true
                if (sizeLocked() >= capacity) {
                    // Shed from strictly lower priority classes only (higher rank number),
                    // oldest first. Never evict input/output to admit bulk/slow-path.
                    var shed = false
                    for (rank in (priority.rank + 1)..PduPriority.entries.size) {
                        val victimQueue = queues[rank - 1]
                        if (victimQueue.isNotEmpty()) {
                            victimQueue.removeFirst() // oldest of that class — dropped
                            shedOrRejectedTotal++
                            shed = true
                            break
                        }
                    }
                    if (!shed) {
                        shedOrRejectedTotal++
                        admitted = false
                    }
                }
                if (!admitted) {
                    null
                } else {
                    val pdu = PrioritizedPdu(++seqCounter, priority, payload, nanoClock())
                    queueFor(priority).addLast(pdu)
                    pdu
                }
            }
        }

        /**
         * Returns the next PDU to dispatch: starved bulk first (see class docs), otherwise
         * the head of the highest-priority non-empty class. Null when empty.
         */
        fun poll(): PrioritizedPdu? = synchronized(lock) {
            val rank = pickRank()
            if (rank == null) null else queues[rank - 1].removeFirst()
        }

        /** Non-destructive variant of [poll]. */
        fun peek(): PrioritizedPdu? = synchronized(lock) {
            val rank = pickRank()
            if (rank == null) null else queues[rank - 1].firstOrNull()
        }

        /**
         * Chooses the rank of the next PDU without mutating the queues:
         * 1. bulk (rank 4) when starved, 2. slow-path (rank 3) when starved,
         * 3. otherwise highest-priority non-empty class.
         */
        private fun pickRank(): Int? {
            val now = nanoClock()
            // Bulk starvation: serve the oldest waiting clipboard/bulk PDU first.
            val bulkHead = queues[PduPriority.BULK_DATA.rank - 1].firstOrNull()
            if (bulkHead != null && now - bulkHead.timestampNanos >= starvationThresholdNanos) {
                return PduPriority.BULK_DATA.rank
            }
            // Slow-path starvation: bounded latency for control PDUs too.
            val slowHead = queues[PduPriority.SLOWPATH_INTERACTIVE.rank - 1].firstOrNull()
            if (slowHead != null && now - slowHead.timestampNanos >= starvationThresholdNanos) {
                return PduPriority.SLOWPATH_INTERACTIVE.rank
            }
            for (rank in 1..PduPriority.entries.size) {
                if (queues[rank - 1].isNotEmpty()) return rank
            }
            return null
        }

        private fun sizeLocked(): Int = queues.sumOf { it.size }

        val size: Int get() = synchronized(lock) { sizeLocked() }
        val isEmpty: Boolean get() = synchronized(lock) { sizeLocked() == 0 }

        fun clear() {
            synchronized(lock) {
                queues.forEach { it.clear() }
            }
        }

        companion object {
            /** Default starvation bound: bulk PDUs wait at most ~500ms behind interactive traffic. */
            const val DEFAULT_STARVATION_THRESHOLD_NANOS: Long = 500_000_000L
        }
    }
}
