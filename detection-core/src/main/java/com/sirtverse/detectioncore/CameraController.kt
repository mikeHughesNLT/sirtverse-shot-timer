package com.sirtverse.detectioncore

import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.lifecycle.LifecycleOwner

/**
 * Camera abstraction — all camera knobs live here.
 *
 * Architecture decision (DECISIONS.md D-007, D-006): the detector and shell
 * never touch CameraX or Camera2 directly; all camera concerns are behind this seam.
 *
 * Lifecycle order:
 *   1. Create [CameraXController].
 *   2. Call [bind] once from the Activity lifecycle.
 *   3. Set [frameListener] any time after [bind]; delivered on the analysis executor
 *      thread — the listener MUST close the [ImageProxy].
 *   4. [setExposure] / [setAutoExposure] take effect immediately after [bind].
 *   5. Call [shutdown] from Activity.onDestroy.
 */
interface CameraController {

    fun capabilities(): CameraCaps

    /**
     * Pin the sensor to a fixed exposure (drill-window mode, DECISIONS.md D-007).
     *
     * @param shutterNs  Sensor exposure time in nanoseconds (e.g. 16_000_000 = 16 ms).
     * @param iso        Sensor sensitivity (e.g. 800).
     */
    fun setExposure(shutterNs: Long, iso: Int)

    /** Restore CameraX auto-exposure. */
    fun setAutoExposure()

    /**
     * Frame sink — called on the analysis executor thread for every captured frame.
     * The listener MUST close [ImageProxy] when done with the buffer.
     * Set to null to stop frame delivery (no rebind needed).
     */
    var frameListener: ((ImageProxy) -> Unit)?

    /**
     * Bind preview and analysis use-cases to [lifecycleOwner].
     *
     * @param previewSurface  Surface for the live preview, or null (analysis-only).
     */
    fun bind(lifecycleOwner: LifecycleOwner, previewSurface: Preview.SurfaceProvider?)

    fun unbind()

    /** Release internal executor — call from Activity.onDestroy. */
    fun shutdown()
}
