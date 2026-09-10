package com.sirtverse.shottimer.airframe

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * CC-SIRT-TRUTH-MODE-001 B3: writes truth events and MISSED/PHANTOM dump packages.
 *
 * - [logEvent]: one row per isShot event (counted or ignored) appended to events.jsonl
 * - [dumpMissed]: dumps the last 2 s of the ring (Mike pressed because no marker appeared)
 * - [dumpPhantom]: dumps a ±1 s window around the most recent counted isShot (marker appeared
 *   but Mike didn't fire)
 *
 * All disk I/O runs on a single background thread; [onDumpComplete] fires on the main thread
 * with the dump folder name so the overlay can show "dumped ✓ <name>".
 *
 * Dump layout under [truthDir]/<ts>-<kind>/:
 *   frame_NNNN.jpg   — JPEG q80 at analysis resolution, one per ring slot
 *   features.jsonl   — one FrameFeatures.toJsonLine() per slot (same index)
 *   meta.json        — rects, exposure, lighting, app version, HEAD hash
 *   peak_frame.jpg   — copy of the highest-score frame (convenience for the report)
 */
class TruthWriter(
    private val context: Context,
    private val ring: FrameRing,
    private val onDumpComplete: (String) -> Unit,
) {
    companion object {
        private const val TAG = "TruthWriter"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioExecutor = Executors.newSingleThreadExecutor()

    // events.jsonl opened on first logEvent call; survives across button presses in a session.
    private var eventsWriter: PrintWriter? = null

    // Track last counted isShot wall-clock ms for PHANTOM centering.
    @Volatile private var lastShotTsMs: Long = -1L

    private val truthDir: File
        get() {
            val base = context.getExternalFilesDir("truth")
                ?: File(context.filesDir, "truth")
            base.mkdirs()
            return base
        }

    private fun ensureEventsWriter() {
        if (eventsWriter != null) return
        try {
            val f = File(truthDir, "events.jsonl")
            eventsWriter = PrintWriter(FileWriter(f, true /* append */))
            Log.i(TAG, "events.jsonl opened: ${f.absolutePath}")
        } catch (e: Exception) {
            Log.w(TAG, "events.jsonl open failed: ${e.message}")
        }
    }

    /**
     * Log one detection event from the Airframe seam (B1).
     *
     * Called for every [Detection.isShot] event, whether counted or ignored.
     */
    fun logEvent(
        tsMs: Long, nx: Double, ny: Double,
        rectIdx: Int, counted: Boolean, reason: String,
        score: Float, gates: String, lighting: String,
    ) {
        if (counted) lastShotTsMs = tsMs
        ioExecutor.execute {
            try {
                ensureEventsWriter()
                eventsWriter?.println(
                    """{"ts":$tsMs,""" +
                    """"nx":${"%.4f".format(nx)},"ny":${"%.4f".format(ny)},""" +
                    """"rect_idx":$rectIdx,"counted":$counted,"reason":"$reason",""" +
                    """"score":${"%.2f".format(score)},"gates":"$gates","lighting":"$lighting"}"""
                )
                eventsWriter?.flush()
            } catch (e: Exception) {
                Log.w(TAG, "logEvent write failed: ${e.message}")
            }
        }
    }

    /**
     * MISSED button: dump the last 2 s of the ring.
     * Mike pressed because he fired but saw no marker.
     */
    fun dumpMissed(meta: DumpMeta) {
        val slots = ring.snapshot()
        writeDump("${meta.tsMs}-MISSED", slots, meta, noRecentDetection = false)
    }

    /**
     * PHANTOM button: dump ±1 s window around the most recent counted isShot.
     * If no detection in the last 3 s, dump the last 2 s and flag no_recent_detection.
     */
    fun dumpPhantom(meta: DumpMeta) {
        val slots = ring.snapshot()
        val shotTs = lastShotTsMs
        val noRecent = shotTs < 0L || (meta.tsMs - shotTs) > 3000L
        val window = if (noRecent) slots
        else slots.filter { abs(it.features.tsMs - shotTs) <= 1000L }.takeIf { it.isNotEmpty() } ?: slots
        writeDump("${meta.tsMs}-PHANTOM", window, meta, noRecentDetection = noRecent)
    }

    /** Metadata written into meta.json inside each dump directory. */
    data class DumpMeta(
        val tsMs: Long,
        val rects: List<TargetRect>,
        val iso: Int,
        val shutterNs: Long,
        val targetLuma: Float,
        val exposureLocked: Boolean,
        val lighting: String,
        val appVersionName: String,
        val headHash: String,
    )

    private fun writeDump(name: String, slots: List<FrameRing.Slot>, meta: DumpMeta, noRecentDetection: Boolean) {
        ioExecutor.execute {
            try {
                val dir = File(truthDir, name)
                dir.mkdirs()

                // features.jsonl + per-frame JPEGs
                PrintWriter(FileWriter(File(dir, "features.jsonl"), false)).use { pw ->
                    slots.forEachIndexed { idx, slot ->
                        pw.println(slot.features.toJsonLine())
                        if (slot.jpeg.isNotEmpty()) {
                            File(dir, "frame_%04d.jpg".format(idx)).writeBytes(slot.jpeg)
                        }
                    }
                }

                // peak_frame.jpg — highest-score frame for quick visual reference
                val peak = slots.maxByOrNull { it.features.score }
                if (peak != null && peak.jpeg.isNotEmpty()) {
                    File(dir, "peak_frame.jpg").writeBytes(peak.jpeg)
                }

                // meta.json
                val rectsJson = TargetRect.listToJson(meta.rects)
                File(dir, "meta.json").writeText(
                    """{"ts_ms":${meta.tsMs},"dump_name":"$name",""" +
                    """"rects":$rectsJson,""" +
                    """"iso":${meta.iso},"shutter_ns":${meta.shutterNs},""" +
                    """"target_luma":${"%.1f".format(meta.targetLuma)},""" +
                    """"exposure_locked":${meta.exposureLocked},""" +
                    """"lighting":"${meta.lighting}",""" +
                    """"app_version":"${meta.appVersionName}",""" +
                    """"head_hash":"${meta.headHash}",""" +
                    """"no_recent_detection":$noRecentDetection,""" +
                    """"frame_count":${slots.size}}"""
                )

                Log.i(TAG, "dump written: ${dir.absolutePath} (${slots.size} frames)")
                mainHandler.post { onDumpComplete(name) }
            } catch (e: Exception) {
                Log.w(TAG, "dump write failed for $name: ${e.message}")
                mainHandler.post { onDumpComplete("$name [write error]") }
            }
        }
    }

    fun close() {
        eventsWriter?.flush()
        eventsWriter?.close()
        eventsWriter = null
        ioExecutor.shutdown()
    }
}
