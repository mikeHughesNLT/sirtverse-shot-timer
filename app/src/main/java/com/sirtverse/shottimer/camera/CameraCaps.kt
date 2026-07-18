package com.sirtverse.shottimer.camera

/**
 * Static capabilities snapshot for one back-facing camera lens, populated after
 * [CameraController.bind] returns.
 *
 * DUALLENS extensibility (SPEC-2026-07-15-DUALLENS-001): a second CameraController
 * instance returns its own CameraCaps independently; the detector never needs to know.
 */
data class CameraCaps(
    /** Camera2 logical/physical camera ID string. */
    val lensId: String,
    /** Sensor exposure time range in nanoseconds, or null if unreadable. */
    val exposureRangeNs: LongRange?,
    /** Sensor sensitivity (ISO) range, or null if unreadable. */
    val isoRange: IntRange?,
    /** Approximate max fps from the Camera2 stream configuration map. */
    val maxFps: Int,
)
