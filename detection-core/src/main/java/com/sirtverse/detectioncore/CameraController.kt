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
     * Set Camera2 AE metering region to the given display-normalized rect (0..1, top-left
     * origin). The implementation maps display coords → sensor-pixel coords accounting for
     * sensor orientation, then writes CONTROL_AE_REGIONS on the repeating request.
     *
     * Only meaningful when AE mode is ON (automatic). In manual exposure (AE_MODE_OFF) the
     * hardware ignores CONTROL_AE_REGIONS — the region is still set so switching to auto
     * picks it up instantly without re-calling.
     *
     * If the phone reports CONTROL_MAX_REGIONS_AE == 0, the call is a no-op and an 'AE∅'
     * indicator appears in the overlay.
     *
     * B3.5 CC-SIRT-TARGET-REGION-001 r2 — camera side only, no detector change.
     */
    fun setAeMeteringRect(normLeft: Float, normTop: Float, normRight: Float, normBottom: Float)

    /** Remove any AE metering rect set via [setAeMeteringRect]. */
    fun clearAeMeteringRect()

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
