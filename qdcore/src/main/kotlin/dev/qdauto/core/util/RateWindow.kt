package dev.qdauto.core.util

/** Frames/s y kbit/s sobre una ventana deslizante. Thread-safe. */
internal class RateWindow(private val windowNanos: Long = 1_000_000_000L) {
    private val times = ArrayDeque<Long>()
    private val sizes = ArrayDeque<Int>()
    private var bytes = 0L

    @Synchronized
    fun add(bytesCount: Int, nowNanos: Long = System.nanoTime()) {
        times.addLast(nowNanos)
        sizes.addLast(bytesCount)
        bytes += bytesCount
        trim(nowNanos)
    }

    /** (frames/s, kbit/s) en la última ventana. */
    @Synchronized
    fun rates(nowNanos: Long = System.nanoTime()): Pair<Double, Double> {
        trim(nowNanos)
        val seconds = windowNanos / 1e9
        return times.size / seconds to bytes * 8 / 1000.0 / seconds
    }

    private fun trim(nowNanos: Long) {
        while (times.isNotEmpty() && nowNanos - times.first() > windowNanos) {
            times.removeFirst()
            bytes -= sizes.removeFirst()
        }
    }
}
