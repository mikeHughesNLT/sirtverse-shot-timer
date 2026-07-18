package com.sirtverse.shottimer.camera

import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.lifecycle.LifecycleOwner

/**
 * Camera abstraction — all camera knobs live here.
 *
 * Architecture decision (DECISIONS.md D-007, D-006): the detector and timer shell
 * never touch CameraX or Camera2 directly; all camera concerns are behind this seam.
 *
 * v1 binds ONE back lens ([CameraXController]).
 *
 * DUALLENS extensibility (SPEC-2026-07-15-DUALLENS-001): a second CameraController
 * instance with its own exposure setting covers the fast/slow shutter pair — the
 * detector and ShotTimerActivity never change.
 *
 * Lifecycle order:
 *   1. Create [CameraXController].
 *   2. Call [bind] once from the Activity lifecycle (attaches Preview + ImageAnalysis).
 *   3. Set [frameListener] any time after [bind]; frames are delivered on the analysis
 *      executor thread — the listener MUST close the [ImageProxy].
 *   4. [setExposure] / [setAutoExposure] take effect immediately after [bind].
 *   5. Call [shutdown] from [android.app.Activity.onDestroy] to release the executor.
 */
interface CameraController {

    /** Lens capabilities — valid after [bind]. Returns a safe default before bind. */
    fun capabilities(): CameraCaps

    /**
     * Pin the sensor to a fixed exposure (drill-window mode, DECISIONS.md D-007).
     * Effective immediately on the running capture session — no rebind needed.
     *
     * @param shutterNs  Sensor exposure time in nanoseconds (e.g. 16_000_000 = 16 ms).
     * @param iso        Sensor sensitivity (e.g. 800).
     */
    fun setExposure(shutterNs: Long, iso: Int)

    /** Restore CameraX auto-exposure. Call from [CameraLaserDetector.stop]. */
    fun setAutoExposure()

    /**
     * Frame sink — called on the analysis executor thread for every captured frame.
     * The listener MUST close [ImageProxy] when it is done with the buffer.
     * Set to null to stop frame delivery (no rebind needed).
     */
    var frameListener: ((ImageProxy) -> Unit)?

    /**
     * Bind preview and analysis use-cases to [lifecycleOwner].
     * Safe to call multiple times; re-binds on each call.
     *
     * @param previewSurface  Surface for the live preview, or null (analysis-only).
     */
    fun bind(lifecycleOwner: LifecycleOwner, previewSurface: Preview.SurfaceProvider?)

    /** Unbind all use-cases and release the camera. */
    fun unbind()

    /** Release internal executor — call from [android.app.Activity.onDestroy]. */
    fun shutdown()
}
