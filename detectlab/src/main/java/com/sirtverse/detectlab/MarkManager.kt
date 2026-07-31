package com.sirtverse.detectlab

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import androidx.camera.core.ImageProxy
import com.sirtverse.detectioncore.Detection
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.SocketTimeoutException
import java.util.concurrent.Executors

/**
 * MARK channel: listens on UDP :9877 for MARK:<seq> from the Conductor.
 *
 * Maintains a ring buffer of the last [RING_SIZE] downscaled frames (~320px JPEG).
 * On MARK, captures [POST_MARK_FRAMES] additional frames, then flushes the combined
 * bundle to getExternalFilesDir("mark_frames")/<seq>/ — each frame gets a .jpg and a
 * .json sidecar with gate results (peakScore, passNeighbor, passColor, isShot).
 *
 * Retrieval: adb pull /sdcard/Android/data/com.sirtverse.detectlab/files/mark_frames/<seq>/ .
 *
 * captureFrame() — call BEFORE the detector processes the image (image still open).
 * annotate()     — call from onDetection on main thread to backfill gate data.
 */
class MarkManager(private val context: Context) {

    companion object {
        private const val TAG = "MarkManager"
        private const val RING_SIZE = 10
        private const val POST_MARK_FRAMES = 5
        private const val MARK_PORT = 9877
        private const val MAX_FRAME_PX = 320
    }

    private class FrameEntry(
        val jpegBytes: ByteArray,
        val imageTimestampNs: Long,
        val epochMs: Long,
    ) {
        var peakScore: Float = 0f
        var passNeighbor: Boolean = false
        var passColor: Boolean = false
        var isShot: Boolean = false
    }

    private val lock = Any()
    private val ring = ArrayDeque<FrameEntry>()
    private var markSeq: String? = null
    private var postMarkRemaining = 0
    private val postMarkExtra = mutableListOf<FrameEntry>()

    @Volatile private var udpRunning = false
    private var udpThread: Thread? = null
    private val flushExecutor = Executors.newSingleThreadExecutor()

    fun start() {
        udpRunning = true
        udpThread = Thread {
            try {
                DatagramSocket(MARK_PORT).use { sock ->
                    sock.soTimeout = 1000
                    val buf = ByteArray(256)
                    while (udpRunning) {
                        try {
                            val pkt = DatagramPacket(buf, buf.size)
                            sock.receive(pkt)
                            val msg = String(pkt.data, 0, pkt.length).trim()
                            if (msg.startsWith("MARK:")) {
                                val seq = msg.removePrefix("MARK:").trim()
                                Log.i(TAG, "MARK:$seq received — arming post-mark capture")
                                synchronized(lock) {
                                    markSeq = seq
                                    postMarkRemaining = POST_MARK_FRAMES
                                    postMarkExtra.clear()
                                }
                            }
                        } catch (_: SocketTimeoutException) {
                            // normal 1-s poll timeout
                        }
                    }
                }
            } catch (e: Exception) {
                if (udpRunning) Log.e(TAG, "UDP listener: ${e.message}")
            }
        }.also {
            it.name = "MarkReceiver"
            it.isDaemon = true
            it.start()
        }
        Log.i(TAG, "started, listening UDP :$MARK_PORT")
    }

    fun stop() {
        udpRunning = false
        flushExecutor.shutdown()
    }

    /** Called on the CameraX analysis executor — image is still open. */
    fun captureFrame(image: ImageProxy) {
        val jpegBytes = encodeJpeg(image) ?: return
        val entry = FrameEntry(
            jpegBytes = jpegBytes,
            imageTimestampNs = image.imageInfo.timestamp,
            epochMs = System.currentTimeMillis(),
        )
        var flushArgs: Pair<String, List<FrameEntry>>? = null
        synchronized(lock) {
            ring.addLast(entry)
            if (ring.size > RING_SIZE) ring.removeFirst()

            val seq = markSeq
            if (seq != null && postMarkRemaining > 0) {
                postMarkExtra.add(entry)
                postMarkRemaining--
                if (postMarkRemaining == 0) {
                    flushArgs = seq to (ring.toList() + postMarkExtra.toList())
                    markSeq = null
                    postMarkExtra.clear()
                }
            }
        }
        flushArgs?.let { (seq, frames) ->
            flushExecutor.execute { flushToDisk(seq, frames) }
        }
    }

    /** Called on main thread from onDetection — backfills gate data into the matching slot. */
    fun annotate(d: Detection) {
        synchronized(lock) {
            val entry = ring.lastOrNull { it.imageTimestampNs == d.timestampNs } ?: return@synchronized
            entry.peakScore = d.peakScore
            entry.passNeighbor = d.passNeighbor
            entry.passColor = d.passColor
            entry.isShot = d.isShot
        }
    }

    private fun flushToDisk(seq: String, frames: List<FrameEntry>) {
        try {
            val dir = File(context.getExternalFilesDir("mark_frames"), seq)
            dir.mkdirs()
            val deduped = frames.distinctBy { it.imageTimestampNs }
            deduped.forEachIndexed { idx, e ->
                val base = "%03d_%d".format(idx, e.epochMs)
                File(dir, "$base.jpg").writeBytes(e.jpegBytes)
                File(dir, "$base.json").writeText(
                    """{"idx":$idx,"epoch_ms":${e.epochMs},"ts_ns":${e.imageTimestampNs},""" +
                    """"peak_score":${"%.2f".format(e.peakScore)},""" +
                    """"pass_neighbor":${e.passNeighbor},"pass_color":${e.passColor},""" +
                    """"is_shot":${e.isShot}}"""
                )
            }
            File(dir, "index.json").writeText(
                """{"seq":"$seq","total":${deduped.size},"dir":"${dir.absolutePath}"}"""
            )
            Log.i(TAG, "MARK flush done seq=$seq frames=${deduped.size} dir=${dir.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "flushToDisk seq=$seq: ${e.message}")
        }
    }

    private fun encodeJpeg(image: ImageProxy): ByteArray? {
        return try {
            val w = image.width
            val h = image.height
            val yPlane = image.planes[0]
            val uPlane = image.planes[1]
            val vPlane = image.planes[2]

            // Duplicate buffers so we don't shift position/limit seen by the detector
            val yBuf = yPlane.buffer.duplicate()
            val uBuf = uPlane.buffer.duplicate()
            val vBuf = vPlane.buffer.duplicate()

            val yRowStride = yPlane.rowStride
            val uvRowStride = vPlane.rowStride
            val uvPixelStride = vPlane.pixelStride

            val nv21 = ByteArray(w * h + w * h / 2)

            // Y plane — row stride may exceed width
            for (row in 0 until h) {
                val src = row * yRowStride
                val dst = row * w
                if (src + w <= yBuf.limit()) {
                    yBuf.position(src)
                    yBuf.get(nv21, dst, w)
                }
            }

            // VU interleaved (NV21 = Y + VU)
            val uvOffset = w * h
            val uvW = w / 2
            val uvH = h / 2
            for (row in 0 until uvH) {
                for (col in 0 until uvW) {
                    val srcIdx = row * uvRowStride + col * uvPixelStride
                    val dstIdx = uvOffset + (row * uvW + col) * 2
                    if (dstIdx + 1 < nv21.size) {
                        nv21[dstIdx]     = if (srcIdx < vBuf.limit()) vBuf.get(srcIdx) else 0
                        nv21[dstIdx + 1] = if (srcIdx < uBuf.limit()) uBuf.get(srcIdx) else 0
                    }
                }
            }

            val yuvImage = YuvImage(nv21, ImageFormat.NV21, w, h, null)
            val baos = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, w, h), 70, baos)
            val fullJpeg = baos.toByteArray()

            if (w <= MAX_FRAME_PX) return fullJpeg

            val bmp = BitmapFactory.decodeByteArray(fullJpeg, 0, fullJpeg.size)
                ?: return fullJpeg
            val scaledH = (MAX_FRAME_PX.toFloat() * h / w).toInt().coerceAtLeast(1)
            val scaled = Bitmap.createScaledBitmap(bmp, MAX_FRAME_PX, scaledH, false)
            bmp.recycle()
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 70, out)
            scaled.recycle()
            out.toByteArray()
        } catch (e: Exception) {
            Log.w(TAG, "encodeJpeg: ${e.message}")
            null
        }
    }
}
