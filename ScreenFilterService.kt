package com.example.nudeblurguard

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

class ScreenFilterService : Service() {

    companion object {
        const val ACTION_START = "START"
        const val ACTION_STOP = "STOP"

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
        const val EXTRA_THRESHOLD = "threshold"
        const val EXTRA_FPS = "fps"
        const val EXTRA_PIXEL_SIZE = "pixel_size"

        private const val CHANNEL_ID = "nudeblur_filter"
        private const val NOTIFICATION_ID = 1001

        // Keep the last successful mask briefly when a detector frame misses.
        private const val DETECTION_HOLD_MS = 450L
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null

    private var detector: NudityDetector? = null

    private var overlay: MosaicOverlayView? = null
    private var overlayWindow: WindowManager? = null

    private val executor = Executors.newSingleThreadExecutor()
    private val processing = AtomicBoolean(false)

    private var lastAnalysisMs = 0L
    private var lastDetectionMs = 0L

    private var analysisIntervalMs = 125L
    private var threshold = 0.55f
    private var pixelSize = 28

    @Volatile
    private var detectorReady = false

    private val projectionCallback =
        object : MediaProjection.Callback() {
            override fun onStop() {
                Handler(Looper.getMainLooper()).post {
                    stopFilter(stopProjection = false)
                    stopSelf()
                }
            }
        }

    override fun onCreate() {
        super.onCreate()

        createNotificationChannel()

        executor.execute {
            runCatching {
                val model =
                    ModelManager.ensureInstalled(this@ScreenFilterService)

                detector =
                    OnnxNudityDetector.create(
                        this@ScreenFilterService,
                        model
                    )

                detectorReady = true
            }.onFailure {
                it.printStackTrace()
                detectorReady = false
            }
        }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        when (intent?.action) {

            ACTION_STOP -> {
                stopFilter()
                stopSelf()
            }

            ACTION_START -> {

                val resultCode =
                    intent.getIntExtra(
                        EXTRA_RESULT_CODE,
                        Activity.RESULT_CANCELED
                    )

                val data =
                    intent.parcelable<Intent>(EXTRA_DATA)

                if (
                    data == null ||
                    resultCode != Activity.RESULT_OK
                ) {
                    stopSelf()
                    return START_NOT_STICKY
                }

                threshold =
                    intent.getFloatExtra(
                        EXTRA_THRESHOLD,
                        0.55f
                    )

                val fps =
                    intent.getIntExtra(
                        EXTRA_FPS,
                        8
                    ).coerceIn(5, 15)

                analysisIntervalMs =
                    1000L / fps

                pixelSize =
                    intent.getIntExtra(
                        EXTRA_PIXEL_SIZE,
                        28
                    ).coerceIn(8, 60)

                try {
                    ServiceCompat.startForeground(
                        this,
                        NOTIFICATION_ID,
                        buildNotification(),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                    stopSelf()
                    return START_NOT_STICKY
                }

                startFilter(
                    resultCode,
                    data
                )
            }
        }

        return START_NOT_STICKY
    }

    private fun startFilter(
        resultCode: Int,
        data: Intent
    ) {
        stopFilter()

        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }

        val manager =
            getSystemService(
                MEDIA_PROJECTION_SERVICE
            ) as MediaProjectionManager

        val newProjection =
            try {
                manager.getMediaProjection(
                    resultCode,
                    data
                )
            } catch (e: Exception) {
                e.printStackTrace()
                stopSelf()
                return
            }

        if (newProjection == null) {
            stopSelf()
            return
        }

        projection = newProjection

        projection!!.registerCallback(
            projectionCallback,
            Handler(Looper.getMainLooper())
        )

        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        reader =
            try {
                ImageReader.newInstance(
                    width,
                    height,
                    PixelFormat.RGBA_8888,
                    2
                )
            } catch (e: Exception) {
                e.printStackTrace()
                stopFilter()
                stopSelf()
                return
            }

        reader?.setOnImageAvailableListener(
            { imageReader ->

                val now =
                    SystemClock.elapsedRealtime()

                if (
                    now - lastAnalysisMs <
                    analysisIntervalMs
                ) {
                    imageReader
                        .acquireLatestImage()
                        ?.close()
                    return@setOnImageAvailableListener
                }

                if (
                    !processing.compareAndSet(
                        false,
                        true
                    )
                ) {
                    imageReader
                        .acquireLatestImage()
                        ?.close()
                    return@setOnImageAvailableListener
                }

                val image =
                    imageReader.acquireLatestImage()

                if (image == null) {
                    processing.set(false)
                    return@setOnImageAvailableListener
                }

                lastAnalysisMs = now

                executor.execute {

                    var bitmap: Bitmap? = null

                    try {
                        bitmap = imageToBitmap(image)

                        val detections =
                            if (detectorReady) {
                                detector
                                    ?.detect(
                                        bitmap,
                                        threshold
                                    )
                                    .orEmpty()
                            } else {
                                emptyList()
                            }

                        val mainHandler =
                            Handler(
                                Looper.getMainLooper()
                            )

                        if (detections.isNotEmpty()) {

                            lastDetectionMs =
                                SystemClock.elapsedRealtime()

                            val prepared =
                                MosaicOverlayView.prepare(
                                    bitmap,
                                    detections,
                                    pixelSize
                                )

                            mainHandler.post {
                                overlay?.setFrame(prepared)
                            }

                        } else if (
                            SystemClock.elapsedRealtime() -
                                lastDetectionMs >
                            DETECTION_HOLD_MS
                        ) {

                            mainHandler.post {
                                overlay?.clearFrame()
                            }
                        }

                    } catch (e: Throwable) {
                        e.printStackTrace()
                    } finally {

                        image.close()

                        bitmap?.let {
                            if (!it.isRecycled) {
                                it.recycle()
                            }
                        }

                        processing.set(false)
                    }
                }
            },
            Handler(Looper.getMainLooper())
        )

        virtualDisplay =
            try {
                projection!!.createVirtualDisplay(
                    "NudeBlurGuard",
                    width,
                    height,
                    density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader!!.surface,
                    null,
                    null
                )
            } catch (e: Throwable) {
                e.printStackTrace()

                stopFilter()
                stopSelf()

                return
            }

        showOverlay(
            width,
            height
        )
    }

    private fun showOverlay(
        width: Int,
        height: Int
    ) {
        overlayWindow =
            getSystemService(
                WINDOW_SERVICE
            ) as WindowManager

        overlay =
            MosaicOverlayView(this)

        val params =
            WindowManager.LayoutParams(
                width,
                height,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity =
                    Gravity.TOP or Gravity.START
            }

        runCatching {
            overlayWindow?.addView(
                overlay,
                params
            )
        }.onFailure {
            overlay = null
        }
    }

    private fun stopFilter(
        stopProjection: Boolean = true
    ) {
        processing.set(false)

        reader?.setOnImageAvailableListener(
            null,
            null
        )

        reader?.close()
        reader = null

        virtualDisplay?.release()
        virtualDisplay = null

        projection?.let { currentProjection ->

            runCatching {
                currentProjection.unregisterCallback(
                    projectionCallback
                )
            }

            if (stopProjection) {
                runCatching {
                    currentProjection.stop()
                }
            }
        }

        projection = null

        overlay?.let { view ->
            runCatching {
                overlayWindow?.removeView(view)
                view.clearFrame()
            }
        }

        overlay = null
        overlayWindow = null

        lastDetectionMs = 0L
        lastAnalysisMs = 0L
    }

    private fun imageToBitmap(
        image: Image
    ): Bitmap {

        val plane =
            image.planes[0]

        val buffer =
            plane.buffer

        val pixelStride =
            plane.pixelStride

        val rowStride =
            plane.rowStride

        val rowPadding =
            rowStride -
                pixelStride * image.width

        val paddedWidth =
            image.width +
                rowPadding / max(
                    pixelStride,
                    1
                )

        val padded =
            Bitmap.createBitmap(
                paddedWidth,
                image.height,
                Bitmap.Config.ARGB_8888
            )

        buffer.rewind()

        padded.copyPixelsFromBuffer(
            buffer
        )

        val cropped =
            Bitmap.createBitmap(
                padded,
                0,
                0,
                image.width,
                image.height
            )

        padded.recycle()

        return cropped
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(
            this,
            CHANNEL_ID
        )
            .setSmallIcon(
                android.R.drawable.ic_menu_view
            )
            .setContentTitle(
                "NudeBlur Guard"
            )
            .setContentText(
                "Bildschirmfilter ist aktiv"
            )
            .setOngoing(true)
            .setCategory(
                NotificationCompat.CATEGORY_SERVICE
            )
            .build()

    private fun createNotificationChannel() {

        val channel =
            NotificationChannel(
                CHANNEL_ID,
                "NudeBlur Guard",
                NotificationManager.IMPORTANCE_LOW
            )

        getSystemService(
            NotificationManager::class.java
        ).createNotificationChannel(channel)
    }

    override fun onDestroy() {

        stopFilter()

        detector?.close()
        detector = null

        detectorReady = false

        executor.shutdownNow()

        super.onDestroy()
    }

    override fun onBind(
        intent: Intent?
    ): IBinder? = null

    @Suppress("DEPRECATION")
    private inline fun <reified T : Parcelable>
        Intent.parcelable(
            key: String
        ): T? {

        return if (
            Build.VERSION.SDK_INT >= 33
        ) {
            getParcelableExtra(
                key,
                T::class.java
            )
        } else {
            getParcelableExtra(
                key
            )
        }
    }
}
