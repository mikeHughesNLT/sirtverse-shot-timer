package com.sirtverse.detectioncore

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.MeteringRectangle
import android.util.Log
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.Executors

/**
 * CameraX implementation of [CameraController].
 *
 * Wraps CameraX preview + ImageAnalysis; exposes exposure control via Camera2Interop
 * (stable in camera-camera2:1.4.0).
 */
class CameraXController(private val context: Context) : CameraController {

    private val TAG = "CameraXController"

    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var cachedCaps: CameraCaps? = null

    override var frameListener: ((ImageProxy) -> Unit)? = null

    // Pending exposure: if setExposure() is called before bind() completes, store and re-apply.
    private var pendingShutterNs: Long? = null
    private var pendingIso: Int? = null

    // B3.5 — pending AE metering rect (display-normalized). Stored so bind() can re-apply.
    private var pendingAeRect: FloatArray? = null  // [left, top, right, bottom] or null
    // Whether the phone supports AE regions (null = not yet queried).
    var aeRegionsSupported: Boolean? = null
        private set

    override fun capabilities(): CameraCaps {
        cachedCaps?.let { return it }
        val cam = camera ?: return CameraCaps("unknown", null, null, 30)
        return queryCaps(cam).also { cachedCaps = it }
    }

    override fun setExposure(shutterNs: Long, iso: Int) {
        pendingShutterNs = shutterNs
        pendingIso = iso
        val cam = camera ?: run { Log.w(TAG, "setExposure deferred: camera not yet bound"); return }
        applyPendingExposure(cam)
    }

    override fun setAutoExposure() {
        pendingShutterNs = null
        pendingIso = null
        val cam = camera ?: return
        Camera2CameraControl.from(cam.cameraControl).clearCaptureRequestOptions()
        Log.i(TAG, "auto-exposure restored")
    }

    override fun setAeMeteringRect(normLeft: Float, normTop: Float, normRight: Float, normBottom: Float) {
        pendingAeRect = floatArrayOf(normLeft, normTop, normRight, normBottom)
        val cam = camera ?: run { Log.w(TAG, "setAeMeteringRect deferred: camera not yet bound"); return }
        applyAeMeteringRect(cam)
    }

    override fun clearAeMeteringRect() {
        pendingAeRect = null
        val cam = camera ?: return
        val info = Camera2CameraInfo.from(cam.cameraInfo)
        val maxRegions = info.getCameraCharacteristic(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0
        if (maxRegions == 0) return
        val control = Camera2CameraControl.from(cam.cameraControl)
        val opts = CaptureRequestOptions.Builder()
            .setCaptureRequestOption(CaptureRequest.CONTROL_AE_REGIONS, emptyArray<MeteringRectangle>())
            .build()
        control.setCaptureRequestOptions(opts)
        Log.i(TAG, "AE metering rect cleared")
    }

    private fun applyAeMeteringRect(cam: Camera) {
        val r = pendingAeRect ?: return
        val info = Camera2CameraInfo.from(cam.cameraInfo)

        val maxRegions = info.getCameraCharacteristic(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0
        if (maxRegions == 0) {
            aeRegionsSupported = false
            Log.w(TAG, "AE regions: CONTROL_MAX_REGIONS_AE=0, not supported on this device")
            return
        }
        aeRegionsSupported = true

        val sensorArray = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            ?: run { Log.w(TAG, "AE regions: SENSOR_INFO_ACTIVE_ARRAY_SIZE unavailable"); return }
        val sensorW = sensorArray.width().toFloat()
        val sensorH = sensorArray.height().toFloat()
        val orientation = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90

        // Map display-normalized rect → sensor-pixel rect, accounting for sensor orientation.
        // Display-normalized: portrait, (0,0)=top-left. Sensor: landscape pixel array.
        val sr4 = sensorRectFromDisplay(r[0], r[1], r[2], r[3], sensorW, sensorH, orientation)
        val sl = sr4[0]; val st = sr4[1]; val sr = sr4[2]; val sb = sr4[3]

        val region = MeteringRectangle(
            sl.coerceIn(0, sensorW.toInt() - 1),
            st.coerceIn(0, sensorH.toInt() - 1),
            (sr - sl).coerceAtLeast(1).coerceAtMost((sensorW.toInt() - sl).coerceAtLeast(1)),
            (sb - st).coerceAtLeast(1).coerceAtMost((sensorH.toInt() - st).coerceAtLeast(1)),
            MeteringRectangle.METERING_WEIGHT_MAX,
        )

        val opts = CaptureRequestOptions.Builder()
            .setCaptureRequestOption(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(region))
            .build()
        Camera2CameraControl.from(cam.cameraControl).setCaptureRequestOptions(opts)
        Log.i(TAG, "AE metering rect → sensor ($sl,$st,${sl + region.width},${st + region.height}) " +
            "orientation=$orientation")
    }

    /**
     * Converts a display-normalized rect [normLeft, normTop, normRight, normBottom] to sensor
     * pixel rect [left, top, right, bottom] for Camera2 CONTROL_AE_REGIONS.
     *
     * Sensor is a landscape pixel array; display orientation is portrait for portrait-primary
     * devices. The mapping depends on [SENSOR_ORIENTATION] (degrees the sensor image needs to
     * be rotated CW to be upright on a portrait display):
     *
     *   0°: sensor and display are co-aligned. Simple scale.
     *  90°: sensor landscape, display portrait. Standard back-camera on most phones.
     * 180°: sensor is upside-down relative to display.
     * 270°: sensor landscape, flipped relative to 90°.
     */
    private fun sensorRectFromDisplay(
        nl: Float, nt: Float, nr: Float, nb: Float,
        sensorW: Float, sensorH: Float, orientation: Int,
    ): List<Int> = when (orientation) {
        90 -> listOf(
            (nt * sensorH).toInt(), ((1f - nr) * sensorW).toInt(),
            (nb * sensorH).toInt(), ((1f - nl) * sensorW).toInt(),
        )
        270 -> listOf(
            ((1f - nb) * sensorH).toInt(), (nl * sensorW).toInt(),
            ((1f - nt) * sensorH).toInt(), (nr * sensorW).toInt(),
        )
        180 -> listOf(
            ((1f - nr) * sensorW).toInt(), ((1f - nb) * sensorH).toInt(),
            ((1f - nl) * sensorW).toInt(), ((1f - nt) * sensorH).toInt(),
        )
        else -> listOf(  // 0 — co-aligned
            (nl * sensorW).toInt(), (nt * sensorH).toInt(),
            (nr * sensorW).toInt(), (nb * sensorH).toInt(),
        )
    }

    private fun applyPendingExposure(cam: Camera) {
        val shutterNs = pendingShutterNs ?: return
        val iso = pendingIso ?: return
        val control = Camera2CameraControl.from(cam.cameraControl)
        val opts = CaptureRequestOptions.Builder()
            .setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_MODE,
                CameraMetadata.CONTROL_AE_MODE_OFF,
            )
            .setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, shutterNs)
            .setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, iso)
            .build()
        control.setCaptureRequestOptions(opts)
        Log.i(TAG, "exposure pinned: shutterNs=$shutterNs iso=$iso")
    }

    override fun bind(lifecycleOwner: LifecycleOwner, previewSurface: Preview.SurfaceProvider?) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val p = future.get()
            provider = p

            val preview = Preview.Builder().build().also { prev ->
                previewSurface?.let { prev.surfaceProvider = it }
            }

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { ia ->
                    ia.setAnalyzer(analysisExecutor) { image ->
                        val listener = frameListener
                        if (listener != null) listener(image) else image.close()
                    }
                }

            p.unbindAll()
            camera = p.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                analysis,
            )
            cachedCaps = null
            aeRegionsSupported = null
            Log.i(TAG, "camera bound")
            camera?.let {
                applyPendingExposure(it)
                applyAeMeteringRect(it)
            }

        }, ContextCompat.getMainExecutor(context))
    }

    override fun unbind() {
        provider?.unbindAll()
        camera = null
        Log.i(TAG, "camera unbound")
    }

    override fun shutdown() {
        analysisExecutor.shutdown()
        Log.i(TAG, "analysis executor shut down")
    }

    private fun queryCaps(cam: Camera): CameraCaps {
        return try {
            val info = Camera2CameraInfo.from(cam.cameraInfo)
            val exposureRange = info.getCameraCharacteristic(
                CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE,
            )?.let { it.lower..it.upper }
            val isoRange = info.getCameraCharacteristic(
                CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE,
            )?.let { it.lower..it.upper }
            CameraCaps(info.cameraId, exposureRange, isoRange, maxFps = 30)
        } catch (e: Exception) {
            Log.w(TAG, "capabilities query failed: ${e.message}")
            CameraCaps("unknown", null, null, 30)
        }
    }
}
