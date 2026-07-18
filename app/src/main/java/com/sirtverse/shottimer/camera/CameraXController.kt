package com.sirtverse.shottimer.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
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
 * (stable in camera-camera2:1.4.0, already in dependencies — no new dep needed).
 *
 * DUALLENS note: a second instance of this class with a different CameraSelector
 * covers the second rear lens — the detector and ShotTimerActivity never change.
 */
class CameraXController(private val context: Context) : CameraController {

    private val TAG = "CameraXController"

    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var cachedCaps: CameraCaps? = null

    override var frameListener: ((ImageProxy) -> Unit)? = null

    // ── Capabilities ─────────────────────────────────────────────────────────

    override fun capabilities(): CameraCaps {
        cachedCaps?.let { return it }
        val cam = camera ?: return CameraCaps("unknown", null, null, 30)
        return queryCaps(cam).also { cachedCaps = it }
    }

    // ── Exposure control (Camera2Interop) ─────────────────────────────────────

    override fun setExposure(shutterNs: Long, iso: Int) {
        val cam = camera ?: run { Log.w(TAG, "setExposure called before bind"); return }
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

    override fun setAutoExposure() {
        val cam = camera ?: return
        Camera2CameraControl.from(cam.cameraControl).clearCaptureRequestOptions()
        Log.i(TAG, "auto-exposure restored")
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

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
                        // Deliver to listener; if null (no active session) just close.
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
            cachedCaps = null   // refresh capabilities on next query
            Log.i(TAG, "camera bound — caps will be lazy-loaded")

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

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun queryCaps(cam: Camera): CameraCaps {
        return try {
            val info = Camera2CameraInfo.from(cam.cameraInfo)
            val exposureRange = info.getCameraCharacteristic(
                CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE,
            )?.let { it.lower..it.upper }
            val isoRange = info.getCameraCharacteristic(
                CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE,
            )?.let { it.lower..it.upper }
            val lensId = info.cameraId
            CameraCaps(lensId, exposureRange, isoRange, maxFps = 30)
        } catch (e: Exception) {
            Log.w(TAG, "capabilities query failed: ${e.message}")
            CameraCaps("unknown", null, null, 30)
        }
    }
}
