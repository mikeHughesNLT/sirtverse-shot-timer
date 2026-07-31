package com.sirtverse.detectlab

import android.util.Log
import com.sirtverse.detectioncore.Detection
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.Executors

/**
 * Broadcasts live detection telemetry on UDP :9876 for the Conductor to steer the rig.
 *
 * Wire format: detectlab:android:<epoch_ms>:<isShot>:<gridCell>:<peakScore>:<normX>:<normY>
 *
 * Throttle: shot frames broadcast immediately; non-shot frames capped at 5 Hz.
 */
class LabBroadcaster {

    companion object {
        private const val TAG = "LabBroadcaster"
        private const val PORT = 9876
        private const val THROTTLE_MS = 200L
    }

    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var socket: DatagramSocket? = null

    // Accessed only on caller thread (main thread via onDetection)
    private var lastNonShotMs = 0L

    fun send(d: Detection) {
        val epochMs = System.currentTimeMillis()
        if (!d.isShot) {
            if (epochMs - lastNonShotMs < THROTTLE_MS) return
            lastNonShotMs = epochMs
        }
        val msg = "detectlab:android:$epochMs:${if (d.isShot) 1 else 0}:${d.gridCell}" +
                ":${"%.2f".format(d.peakScore)}:${"%.4f".format(d.normX)}:${"%.4f".format(d.normY)}"
        executor.execute {
            try {
                val s = socket ?: DatagramSocket().also {
                    it.broadcast = true
                    socket = it
                }
                val bytes = msg.toByteArray(Charsets.UTF_8)
                s.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName("255.255.255.255"), PORT))
            } catch (e: Exception) {
                Log.w(TAG, "send: ${e.message}")
            }
        }
    }

    fun close() {
        executor.shutdown()
        socket?.close()
        socket = null
    }
}
