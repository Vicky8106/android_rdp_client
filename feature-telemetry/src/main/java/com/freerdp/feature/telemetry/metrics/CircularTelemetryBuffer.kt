package com.freerdp.feature.telemetry.metrics

import java.util.Arrays
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * High-performance, zero-garbage circular ring buffer for real-time telemetry metrics.
 *
 * Pre-allocates a fixed-size primitive array ([capacity] = 1,000 samples).
 * Overwrites oldest values once full. Computes running averages, min, max,
 * standard deviation (jitter), and percentiles (p50, p95, p99) in a thread-safe manner.
 */
class CircularTelemetryBuffer(val capacity: Int = 1000) {

    init {
        require(capacity > 0) { "Buffer capacity must be greater than 0, got $capacity" }
    }

    private val buffer = DoubleArray(capacity)

    // Pre-allocated scratch for zero-allocation percentile computation (sorted in place
    // under [lock]; never escapes the class).
    private val sortScratch = DoubleArray(capacity)
    private var head = 0
    private var count = 0
    private val lock = Any()

    /**
     * Records a new sample into the circular buffer.
     */
    fun record(value: Double) = synchronized(lock) {
        buffer[head] = value
        head = (head + 1) % capacity
        if (count < capacity) {
            count++
        }
    }

    fun record(value: Long) = record(value.toDouble())

    /**
     * Current number of valid samples stored in the buffer (0 <= size <= capacity).
     */
    fun size(): Int = synchronized(lock) { count }

    fun isEmpty(): Boolean = synchronized(lock) { count == 0 }

    fun isFull(): Boolean = synchronized(lock) { count == capacity }

    /**
     * Clears all samples in the buffer.
     */
    fun clear() = synchronized(lock) {
        head = 0
        count = 0
        Arrays.fill(buffer, 0.0)
    }

    /**
     * Returns an array of current active samples.
     */
    fun snapshot(): DoubleArray = synchronized(lock) {
        if (count == 0) return DoubleArray(0)
        val result = DoubleArray(count)
        if (count < capacity) {
            System.arraycopy(buffer, 0, result, 0, count)
        } else {
            // Buffer has wrapped: older elements start at 'head'
            val firstChunk = capacity - head
            System.arraycopy(buffer, head, result, 0, firstChunk)
            if (head > 0) {
                System.arraycopy(buffer, 0, result, firstChunk, head)
            }
        }
        return result
    }

    /**
     * Running arithmetic mean.
     */
    fun average(): Double = synchronized(lock) {
        if (count == 0) return 0.0
        var sum = 0.0
        for (i in 0 until count) {
            sum += buffer[i]
        }
        return sum / count
    }

    /**
     * Minimum value in the buffer.
     */
    fun min(): Double = synchronized(lock) {
        if (count == 0) return 0.0
        var minVal = buffer[0]
        for (i in 1 until count) {
            if (buffer[i] < minVal) {
                minVal = buffer[i]
            }
        }
        return minVal
    }

    /**
     * Maximum value in the buffer.
     */
    fun max(): Double = synchronized(lock) {
        if (count == 0) return 0.0
        var maxVal = buffer[0]
        for (i in 1 until count) {
            if (buffer[i] > maxVal) {
                maxVal = buffer[i]
            }
        }
        return maxVal
    }

    /**
     * Population standard deviation (used to measure inter-frame delivery jitter):
     * sigma = sqrt( (1 / N) * sum( (x_i - mean)^2 ) )
     */
    fun standardDeviation(): Double = synchronized(lock) {
        if (count <= 1) return 0.0
        var sum = 0.0
        for (i in 0 until count) {
            sum += buffer[i]
        }
        val mean = sum / count

        var varianceSum = 0.0
        for (i in 0 until count) {
            val diff = buffer[i] - mean
            varianceSum += diff * diff
        }
        return sqrt(varianceSum / count)
    }

    /**
     * Computes the requested percentile (0.0 to 100.0) with linear interpolation.
     * Common percentiles: 50.0 (median), 95.0 (p95), 99.0 (p99).
     *
     * Hot-path safe: copies the logical window into a pre-allocated scratch array and
     * sorts that range in place — no allocation per call (unlike [snapshot].sort()).
     */
    fun percentile(p: Double): Double {
        require(p in 0.0..100.0) { "Percentile must be in 0.0..100.0 range, got $p" }
        synchronized(lock) {
            if (count == 0) return 0.0

            // Copy samples in logical (oldest..newest) order, mirroring snapshot().
            if (count < capacity) {
                System.arraycopy(buffer, 0, sortScratch, 0, count)
            } else {
                val firstChunk = capacity - head
                System.arraycopy(buffer, head, sortScratch, 0, firstChunk)
                if (head > 0) {
                    System.arraycopy(buffer, 0, sortScratch, firstChunk, head)
                }
            }

            if (count == 1) return sortScratch[0]
            Arrays.sort(sortScratch, 0, count)

            val rank = (p / 100.0) * (count - 1)
            val lowIndex = floor(rank).toInt()
            val highIndex = (lowIndex + 1).coerceAtMost(count - 1)
            val fraction = rank - lowIndex

            return sortScratch[lowIndex] + fraction * (sortScratch[highIndex] - sortScratch[lowIndex])
        }
    }
}
