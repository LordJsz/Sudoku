package com.sudoku.solver.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import com.sudoku.solver.R
import com.sudoku.solver.util.Constants
import java.nio.ByteBuffer

/**
 * Foreground service for capturing screen using MediaProjection API
 */
class ScreenCaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.w(TAG, "MediaProjection stopped by Android or the user")
            releaseCaptureResourcesAfterProjectionStop()
            stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        startForeground(Constants.NOTIFICATION_ID, createNotification())
        setupScreenMetrics()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_CAPTURE -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val data = intent.getParcelableExtra<Intent>(EXTRA_DATA)
                if (resultCode == Activity.RESULT_OK && data != null) {
                    if (!startCapture(resultCode, data)) {
                        stopSelf(startId)
                    }
                } else {
                    Log.w(TAG, "Capture start rejected because MediaProjection permission is missing")
                    stopSelf(startId)
                }
            }
            ACTION_STOP_CAPTURE -> {
                stopCapture()
                stopSelf()
            }
            else -> {
                // MediaProjection permission is a one-time system grant and cannot be
                // reconstructed when Android restarts this service after a reboot or
                // process death. Keeping that empty service alive makes MainActivity
                // think capture is running and leaves its Start button disabled.
                Log.w(TAG, "Stopping stale screen-capture service without a start grant")
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopCapture()
        instance = null
    }

    private fun setupScreenMetrics(): Boolean {
        // A Service is not a visual Context. Calling Context.getDisplay() from
        // here crashes on Android 12+ on devices that enforce that distinction
        // (including Huawei/EMUI). A display context is safe for background
        // components and still gives MediaProjection the physical dimensions.
        val metrics = try {
            val displayManager = getSystemService(DisplayManager::class.java)
            val defaultDisplay = displayManager?.getDisplay(Display.DEFAULT_DISPLAY)
            if (defaultDisplay != null) {
                // MediaProjection and Accessibility gestures must use the same
                // physical coordinate space. resources.displayMetrics reports
                // only the app area on some Huawei builds (1228x2444), causing
                // Android to letterbox the 1228x2700 screen and offset every tap.
                DisplayMetrics().also { physicalMetrics ->
                    @Suppress("DEPRECATION")
                    defaultDisplay.getRealMetrics(physicalMetrics)
                }
            } else {
                resources.displayMetrics
            }
        } catch (error: RuntimeException) {
            Log.w(TAG, "Display context unavailable; using resource metrics", error)
            resources.displayMetrics
        }

        val newWidth = metrics.widthPixels.coerceAtLeast(1)
        val newHeight = metrics.heightPixels.coerceAtLeast(1)
        val newDensity = metrics.densityDpi.coerceAtLeast(DisplayMetrics.DENSITY_DEFAULT)
        val changed = newWidth != screenWidth || newHeight != screenHeight || newDensity != screenDensity

        screenWidth = newWidth
        screenHeight = newHeight
        screenDensity = newDensity
        if (changed) {
            Log.i(TAG, "Physical capture metrics: ${screenWidth}x$screenHeight @${screenDensity}dpi")
        }
        return changed
    }

    @Synchronized
    private fun startCapture(resultCode: Int, data: Intent): Boolean {
        val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = try {
            mediaProjectionManager.getMediaProjection(resultCode, data)
        } catch (error: RuntimeException) {
            Log.e(TAG, "MediaProjection permission is no longer valid", error)
            null
        }

        if (projection == null) {
            Log.e(TAG, "Failed to create MediaProjection")
            return false
        }

        // Android 14+ requires this callback before createVirtualDisplay, and a
        // projection permission token may only create one VirtualDisplay.
        projection.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))
        mediaProjection = projection

        // Re-read physical metrics in case the device rotated while the system
        // screen-capture permission dialog was open.
        setupScreenMetrics()
        if (!createCaptureSurface()) {
            stopCapture()
            return false
        }
        Log.i(TAG, "Screen capture started")
        return true
    }

    @Synchronized
    fun isCaptureActive(): Boolean {
        return mediaProjection != null && virtualDisplay != null && imageReader != null
    }

    @Synchronized
    fun captureDimensions(): Pair<Int, Int>? {
        return if (screenWidth > 0 && screenHeight > 0) Pair(screenWidth, screenHeight) else null
    }

    private fun createCaptureSurface(): Boolean {
        val projection = mediaProjection ?: return false
        val reader = try {
            ImageReader.newInstance(
                screenWidth,
                screenHeight,
                PixelFormat.RGBA_8888,
                2
            )
        } catch (error: RuntimeException) {
            Log.e(TAG, "Unable to create capture ImageReader", error)
            return false
        }

        return try {
            val display = projection.createVirtualDisplay(
                "SudokuCapture",
                screenWidth,
                screenHeight,
                screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                null
            )
            if (display == null) {
                reader.close()
                false
            } else {
                imageReader = reader
                virtualDisplay = display
                true
            }
        } catch (error: RuntimeException) {
            reader.close()
            Log.e(TAG, "Unable to create MediaProjection virtual display", error)
            false
        }
    }

    @Synchronized
    private fun stopCapture() {
        virtualDisplay?.release()
        imageReader?.close()
        virtualDisplay = null
        imageReader = null
        val projection = mediaProjection
        mediaProjection = null
        if (projection != null) {
            try {
                projection.unregisterCallback(projectionCallback)
            } catch (_: RuntimeException) {
                // The platform may already have completed the stop callback.
            }
            try {
                projection.stop()
            } catch (error: RuntimeException) {
                Log.w(TAG, "MediaProjection was already stopped", error)
            }
        }

        Log.i(TAG, "Screen capture stopped")
    }

    @Synchronized
    private fun releaseCaptureResourcesAfterProjectionStop() {
        virtualDisplay?.release()
        imageReader?.close()
        virtualDisplay = null
        imageReader = null
        mediaProjection = null
    }

    /**
     * Capture a single fresh frame from the screen.
     * Drains stale frames first, then waits briefly for a new frame.
     */
    @Synchronized
    fun captureScreen(): Bitmap? {
        refreshCaptureForCurrentDisplay()
        val image = acquireFreshImage() ?: return null

        try {
            return createScreenBitmap(image)
        } catch (e: Exception) {
            Log.e(TAG, "Error capturing screen", e)
            return null
        } finally {
            image.close()
        }
    }

    /**
     * Capture only a requested physical-screen region. Key-cell monitoring uses
     * this path to avoid allocating and then cropping a ~9 MB full-screen bitmap
     * on every poll.
     */
    @Synchronized
    fun captureRegion(requestedBounds: Rect, freshFrameWaitMs: Long = FRESH_FRAME_WAIT_MS): Bitmap? {
        refreshCaptureForCurrentDisplay()
        val image = acquireFreshImage(freshFrameWaitMs.coerceIn(1, FRESH_FRAME_WAIT_MS)) ?: return null

        try {
            val left = requestedBounds.left.coerceIn(0, screenWidth - 1)
            val top = requestedBounds.top.coerceIn(0, screenHeight - 1)
            val right = requestedBounds.right.coerceIn(left + 1, screenWidth)
            val bottom = requestedBounds.bottom.coerceIn(top + 1, screenHeight)
            return createRegionBitmap(image, Rect(left, top, right, bottom))
        } catch (e: Exception) {
            Log.e(TAG, "Error capturing screen region $requestedBounds", e)
            return null
        } finally {
            image.close()
        }
    }

    private fun acquireFreshImage(freshFrameWaitMs: Long = FRESH_FRAME_WAIT_MS): Image? {
        val reader = imageReader ?: run {
            Log.e(TAG, "ImageReader not initialized")
            return null
        }

        // Drain any stale buffered frame before waiting for a fresh one.
        reader.acquireLatestImage()?.close()
        Thread.sleep(freshFrameWaitMs)

        // Fire OS 7 can take over a second to publish after the capture surface
        // is recreated for a rotation, so keep the existing bounded retry path.
        for (attempt in 1..FRESH_FRAME_MAX_ATTEMPTS) {
            val image = reader.acquireLatestImage()
            if (image != null) return image
            Thread.sleep(FRESH_FRAME_RETRY_MS)
        }

        Log.w(TAG, "Failed to acquire image after retries")
        return null
    }

    private fun createScreenBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val pixelStride = plane.pixelStride
        val rowPadding = plane.rowStride - pixelStride * screenWidth
        val paddedBitmap = Bitmap.createBitmap(
            screenWidth + rowPadding / pixelStride,
            screenHeight,
            Bitmap.Config.ARGB_8888
        )
        paddedBitmap.copyPixelsFromBuffer(plane.buffer)

        if (rowPadding == 0) return paddedBitmap
        return try {
            Bitmap.createBitmap(paddedBitmap, 0, 0, screenWidth, screenHeight)
        } finally {
            // Bitmap.createBitmap returns a distinct bitmap for this crop. The
            // padded source used to survive until GC and amplified capture churn.
            paddedBitmap.recycle()
        }
    }

    private fun createRegionBitmap(image: Image, bounds: Rect): Bitmap {
        val plane = image.planes[0]
        val pixelStride = plane.pixelStride
        if (pixelStride != ARGB_8888_BYTES_PER_PIXEL) {
            // RGBA_8888 should always be four bytes per pixel, but preserve a
            // safe fallback for vendor-specific ImageReader implementations.
            val fullBitmap = createScreenBitmap(image)
            return try {
                Bitmap.createBitmap(
                    fullBitmap,
                    bounds.left,
                    bounds.top,
                    bounds.width(),
                    bounds.height()
                )
            } finally {
                fullBitmap.recycle()
            }
        }

        val bytesPerOutputRow = bounds.width() * pixelStride
        val outputBuffer = ByteBuffer.allocateDirect(bytesPerOutputRow * bounds.height())
        val sourceBuffer = plane.buffer.duplicate()
        val rowBytes = ByteArray(bytesPerOutputRow)

        for (y in bounds.top until bounds.bottom) {
            sourceBuffer.position(y * plane.rowStride + bounds.left * pixelStride)
            sourceBuffer.get(rowBytes)
            outputBuffer.put(rowBytes)
        }
        outputBuffer.rewind()

        val bitmap = Bitmap.createBitmap(bounds.width(), bounds.height(), Bitmap.Config.ARGB_8888)
        try {
            bitmap.copyPixelsFromBuffer(outputBuffer)
            return bitmap
        } catch (error: Throwable) {
            bitmap.recycle()
            throw error
        }
    }

    /**
     * Keep capture and accessibility gesture coordinates aligned after any
     * phone/tablet rotation without consuming a second projection grant.
     */
    private fun refreshCaptureForCurrentDisplay() {
        val projection = mediaProjection ?: return
        val metricsChanged = setupScreenMetrics()
        if (!metricsChanged) return
        val display = virtualDisplay ?: return
        if (projection != mediaProjection) return

        val replacementReader = try {
            ImageReader.newInstance(
                screenWidth,
                screenHeight,
                PixelFormat.RGBA_8888,
                2
            )
        } catch (error: RuntimeException) {
            Log.e(TAG, "Unable to resize capture ImageReader", error)
            return
        }

        try {
            // Android 14 rejects a second createVirtualDisplay call for the same
            // permission. Resize the existing display and swap only its surface.
            display.resize(screenWidth, screenHeight, screenDensity)
            display.setSurface(replacementReader.surface)
            imageReader?.close()
            imageReader = replacementReader
            Log.i(TAG, "Capture surface resized after rotation: ${screenWidth}x$screenHeight")
            Thread.sleep(650)
        } catch (error: RuntimeException) {
            replacementReader.close()
            Log.e(TAG, "Unable to resize capture surface after rotation", error)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                Constants.NOTIFICATION_CHANNEL_ID,
                "Sudoku Solver Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Screen capture and auto-solve service"
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, Constants.NOTIFICATION_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setContentTitle("Sudoku Solver")
            .setContentText("Screen capture active")
            .setSmallIcon(R.drawable.ic_sudoku)
            .build()
    }

    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val ARGB_8888_BYTES_PER_PIXEL = 4
        private const val FRESH_FRAME_WAIT_MS = 200L
        private const val FRESH_FRAME_RETRY_MS = 150L
        private const val FRESH_FRAME_MAX_ATTEMPTS = 15

        const val ACTION_START_CAPTURE = "com.sudoku.solver.START_CAPTURE"
        const val ACTION_STOP_CAPTURE = "com.sudoku.solver.STOP_CAPTURE"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"

        /**
         * Get the shared instance of the service for capturing screens
         * Note: This is a simplified approach. In production, use a proper singleton pattern or DI
         */
        var instance: ScreenCaptureService? = null
            private set
    }
}
