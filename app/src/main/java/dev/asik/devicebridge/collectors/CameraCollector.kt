package dev.asik.devicebridge.collectors

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Base64
import android.util.Size
import dev.asik.devicebridge.hub.StreamHub
import dev.asik.devicebridge.model.CameraMeta
import dev.asik.devicebridge.model.CaptureMeta
import dev.asik.devicebridge.model.CaptureResponse
import dev.asik.devicebridge.util.PermissionHelper
import dev.asik.devicebridge.util.TimeUtil
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Still capture via Camera2. Keeps last JPEG on disk for GET /v1/camera/last.jpg.
 */
class CameraCollector(
    private val context: Context,
    private val hub: StreamHub,
) {
    private val capturing = AtomicBoolean(false)
    private val lastFile: File
        get() = File(context.cacheDir, "last.jpg")

    fun lastJpegFile(): File? = lastFile.takeIf { it.exists() && it.length() > 0 }

    suspend fun capture(cameraId: String, includeBase64: Boolean = false): CaptureResponse {
        if (!PermissionHelper.hasCamera(context)) {
            error("CAMERA permission not granted")
        }
        if (!capturing.compareAndSet(false, true)) {
            error("camera busy")
        }
        return try {
            withTimeout(20_000) {
                captureInternal(cameraId, includeBase64)
            }
        } finally {
            capturing.set(false)
        }
    }

    private suspend fun captureInternal(cameraId: String, includeBase64: Boolean): CaptureResponse {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val thread = HandlerThread("camera-capture").apply { start() }
        val handler = Handler(thread.looper)
        try {
            val jpeg = openAndCapture(cm, cameraId, handler)
            lastFile.writeBytes(jpeg)
            val meta = CaptureMeta(
                camera_id = cameraId,
                path = lastFile.absolutePath,
                bytes = jpeg.size,
                captured_at = TimeUtil.nowIso(),
            )
            hub.publishCameraMeta(CameraMeta(active_camera_id = cameraId, last_capture = meta))
            return CaptureResponse(
                ok = true,
                capture = meta,
                base64_jpeg = if (includeBase64) {
                    Base64.encodeToString(jpeg, Base64.NO_WRAP)
                } else {
                    null
                },
            )
        } finally {
            thread.quitSafely()
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun openAndCapture(
        cm: CameraManager,
        cameraId: String,
        handler: Handler,
    ): ByteArray = suspendCancellableCoroutine { cont ->
        val chars = cm.getCameraCharacteristics(cameraId)
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: run {
                cont.resumeWithException(IllegalStateException("no stream config"))
                return@suspendCancellableCoroutine
            }
        val sizes = map.getOutputSizes(ImageFormat.JPEG) ?: emptyArray()
        val size = sizes.maxByOrNull { it.width.toLong() * it.height } ?: Size(1280, 720)
        val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 2)

        var device: CameraDevice? = null
        var session: CameraCaptureSession? = null

        fun cleanup() {
            runCatching { session?.close() }
            runCatching { device?.close() }
            runCatching { reader.close() }
        }

        cont.invokeOnCancellation { cleanup() }

        reader.setOnImageAvailableListener({ r ->
            val image = r.acquireNextImage()
            try {
                val buffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                if (cont.isActive) {
                    cont.resume(bytes)
                }
            } catch (e: Exception) {
                if (cont.isActive) cont.resumeWithException(e)
            } finally {
                image.close()
                cleanup()
            }
        }, handler)

        cm.openCamera(
            cameraId,
            object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    device = camera
                    try {
                        @Suppress("DEPRECATION")
                        camera.createCaptureSession(
                            listOf(reader.surface),
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(s: CameraCaptureSession) {
                                    session = s
                                    try {
                                        val req = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                                        req.addTarget(reader.surface)
                                        req.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                                        req.set(CaptureRequest.JPEG_QUALITY, 90.toByte())
                                        s.capture(req.build(), null, handler)
                                    } catch (e: Exception) {
                                        cleanup()
                                        if (cont.isActive) cont.resumeWithException(e)
                                    }
                                }

                                override fun onConfigureFailed(s: CameraCaptureSession) {
                                    cleanup()
                                    if (cont.isActive) {
                                        cont.resumeWithException(IllegalStateException("capture session failed"))
                                    }
                                }
                            },
                            handler,
                        )
                    } catch (e: Exception) {
                        cleanup()
                        if (cont.isActive) cont.resumeWithException(e)
                    }
                }

                override fun onDisconnected(camera: CameraDevice) {
                    cleanup()
                    if (cont.isActive) cont.resumeWithException(IllegalStateException("camera disconnected"))
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    cleanup()
                    if (cont.isActive) cont.resumeWithException(IllegalStateException("camera error $error"))
                }
            },
            handler,
        )
    }
}
