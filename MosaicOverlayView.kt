package com.example.nudeblurguard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * The expensive crop/downsample work is prepared off the UI thread.
 * onDraw() only draws already prepared mosaic patches.
 */
class MosaicOverlayView(context: Context) : View(context) {

    private val paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            isFilterBitmap = false
        }

    private var frame: OverlayFrame? = null

    fun setFrame(newFrame: OverlayFrame?) {
        frame?.recycle()
        frame = newFrame
        postInvalidateOnAnimation()
    }

    fun clearFrame() {
        setFrame(null)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val current = frame ?: return

        val sx = width.toFloat() / current.sourceWidth
        val sy = height.toFloat() / current.sourceHeight

        for (patch in current.patches) {
            val left = max(0f, patch.box.left * sx)
            val top = max(0f, patch.box.top * sy)
            val right = min(width.toFloat(), patch.box.right * sx)
            val bottom = min(height.toFloat(), patch.box.bottom * sy)

            if (right <= left || bottom <= top) {
                continue
            }

            canvas.drawBitmap(
                patch.bitmap,
                null,
                RectF(left, top, right, bottom),
                paint
            )
        }
    }

    override fun onDetachedFromWindow() {
        frame?.recycle()
        frame = null
        super.onDetachedFromWindow()
    }

    data class OverlayFrame(
        val sourceWidth: Int,
        val sourceHeight: Int,
        val patches: List<Patch>
    ) {
        fun recycle() {
            patches.forEach { patch ->
                if (!patch.bitmap.isRecycled) {
                    patch.bitmap.recycle()
                }
            }
        }
    }

    data class Patch(
        val box: RectF,
        val bitmap: Bitmap
    )

    companion object {

        /**
         * Must be called off the main/UI thread.
         */
        fun prepare(
            frame: Bitmap,
            detections: List<Detection>,
            pixelSize: Int
        ): OverlayFrame {

            val safePixelSize =
                pixelSize.coerceIn(8, 60)

            val patches =
                ArrayList<Patch>(detections.size)

            try {
                for (detection in detections) {

                    val left =
                        detection.box.left
                            .toInt()
                            .coerceIn(0, frame.width - 1)

                    val top =
                        detection.box.top
                            .toInt()
                            .coerceIn(0, frame.height - 1)

                    val right =
                        detection.box.right
                            .toInt()
                            .coerceIn(left + 1, frame.width)

                    val bottom =
                        detection.box.bottom
                            .toInt()
                            .coerceIn(top + 1, frame.height)

                    val crop =
                        Bitmap.createBitmap(
                            frame,
                            left,
                            top,
                            right - left,
                            bottom - top
                        )

                    val tiny =
                        Bitmap.createScaledBitmap(
                            crop,
                            max(1, crop.width / safePixelSize),
                            max(1, crop.height / safePixelSize),
                            false
                        )

                    crop.recycle()

                    patches += Patch(
                        box = RectF(
                            detection.box
                        ),
                        bitmap = tiny
                    )
                }

                return OverlayFrame(
                    sourceWidth = frame.width,
                    sourceHeight = frame.height,
                    patches = patches
                )

            } catch (t: Throwable) {
                patches.forEach {
                    if (!it.bitmap.isRecycled) {
                        it.bitmap.recycle()
                    }
                }
                throw t
            }
        }
    }
}
