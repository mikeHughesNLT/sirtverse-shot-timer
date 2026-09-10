package com.sirtverse.shottimer.airframe

import java.util.ArrayDeque

/**
 * In-memory ring buffer of the last [capacityFrames] analysis frames.
 *
 * CC-SIRT-TRUTH-MODE-001 B2: default capacity = 60 frames (~2 s at 30 fps).
 * Each slot holds a JPEG q80 of the analysis frame and a [FrameFeatures] row.
 * Memory footprint: at q80, a ~320×240 analysis frame ≈ 10–25 KB; 60 frames ≈ 0.6–1.5 MB.
 *
 * Thread-safe: all mutations are `@Synchronized` on `this`. The ring is fed from the
 * main thread (via [onDetection]) and consumed from the UI thread (dump on button press) —
 * both are the same thread in practice, but the lock is cheap and makes the contract explicit.
 */
class FrameRing(val capacityFrames: Int = 60) {

    /** One captured analysis frame: compressed image + detection features. */
    data class Slot(val jpeg: ByteArray, val features: FrameFeatures)

    private val deque = ArrayDeque<Slot>(capacityFrames + 1)

    @Synchronized
    fun push(jpeg: ByteArray, features: FrameFeatures) {
        deque.addLast(Slot(jpeg, features))
        while (deque.size > capacityFrames) deque.removeFirst()
    }

    /** Full snapshot (oldest → newest) at the moment of call. */
    @Synchronized
    fun snapshot(): List<Slot> = ArrayList(deque)

    /** Newest [n] slots (for PHANTOM centering). */
    @Synchronized
    fun tail(n: Int): List<Slot> {
        val all = ArrayList(deque)
        return if (all.size <= n) all else all.subList(all.size - n, all.size)
    }

    @Synchronized
    fun size(): Int = deque.size
}
