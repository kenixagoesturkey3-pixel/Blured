package com.example.nudeblurguard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import ai.onnxruntime.*
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

class OnnxNudityDetector private constructor(
    private val modelContext: Context,
    modelFile: java.io.File
) : NudityDetector {

    private val env = OrtEnvironment.getEnvironment()

    private val session: OrtSession =
        env.createSession(
            modelFile.absolutePath,
            OrtSession.SessionOptions()
        )

    private val inputName: String =
        session.inputNames.first()

    private val inputSize = 320

    private val labels = arrayOf(
        "FEMALE_GENITALIA_COVERED",
        "FACE_FEMALE",
        "BUTTOCKS_EXPOSED",
        "FEMALE_BREAST_EXPOSED",
        "FEMALE_GENITALIA_EXPOSED",
        "MALE_BREAST_EXPOSED",
        "ANUS_EXPOSED",
        "FEET_EXPOSED",
        "BELLY_COVERED",
        "FEET_COVERED",
        "ARMPITS_COVERED",
        "ARMPITS_EXPOSED",
        "FACE_MALE",
        "BELLY_EXPOSED",
        "MALE_GENITALIA_EXPOSED",
        "ANUS_COVERED",
        "FEMALE_BREAST_COVERED",
        "BUTTOCKS_COVERED"
    )

    private val censorClasses = setOf(
        "BUTTOCKS_EXPOSED",
        "FEMALE_BREAST_EXPOSED",
        "FEMALE_GENITALIA_EXPOSED",
        "MALE_BREAST_EXPOSED",
        "ANUS_EXPOSED",
        "MALE_GENITALIA_EXPOSED"
    )

    init {
        android.util.Log.d(
            "NudeBlurDetector",
            "ONNX model loaded: ${modelFile.absolutePath}"
        )

        android.util.Log.d(
            "NudeBlurDetector",
            "Input name: $inputName"
        )

        android.util.Log.d(
            "NudeBlurDetector",
            "Inputs: ${session.inputInfo.keys}"
        )

        android.util.Log.d(
            "NudeBlurDetector",
            "Outputs: ${session.outputInfo.keys}"
        )
    }

    companion object {

        fun create(
            context: Context,
            modelFile: java.io.File
        ): OnnxNudityDetector {
            return OnnxNudityDetector(
                context,
                modelFile
            )
        }
    }

    override fun detect(
        bitmap: Bitmap,
        threshold: Float
    ): List<Detection> {

        if (bitmap.width <= 0 || bitmap.height <= 0) {
            return emptyList()
        }

        val prepared =
            letterbox(
                bitmap,
                inputSize
            )

        val tensor =
            OnnxTensor.createTensor(
                env,
                FloatBuffer.wrap(prepared.data),
                longArrayOf(
                    1,
                    3,
                    inputSize.toLong(),
                    inputSize.toLong()
                )
            )

        tensor.use {

            val result =
                session.run(
                    mapOf(
                        inputName to tensor
                    )
                )

            result.use {

                val raw =
                    it[0].value

                return decode(
                    raw = raw,
                    prepared = prepared,
                    imageWidth = bitmap.width,
                    imageHeight = bitmap.height,
                    threshold = threshold
                )
            }
        }
    }

    /**
     * Resize + letterbox auf 320x320.
     *
     * WICHTIG:
     * scale ist der Faktor ORIGINAL -> RESIZED.
     *
     * Beispiel:
     * 1080 -> 144 Pixel
     *
     * scale = 144 / 1080 = 0.1333
     *
     * Beim Zurückrechnen:
     * xOriginal = xModel / scale
     */
    private fun letterbox(
        src: Bitmap,
        size: Int
    ): Prepared {

        val srcWidth = src.width.toFloat()
        val srcHeight = src.height.toFloat()

        val scale =
            min(
                size.toFloat() / srcWidth,
                size.toFloat() / srcHeight
            )

        val newWidth =
            max(
                1,
                round(srcWidth * scale).toInt()
            )

        val newHeight =
            max(
                1,
                round(srcHeight * scale).toInt()
            )

        val resized =
            Bitmap.createScaledBitmap(
                src,
                newWidth,
                newHeight,
                true
            )

        val canvasBitmap =
            Bitmap.createBitmap(
                size,
                size,
                Bitmap.Config.ARGB_8888
            )

        val canvas =
            android.graphics.Canvas(
                canvasBitmap
            )

        canvas.drawColor(
            android.graphics.Color.BLACK
        )

        val padLeft =
            (size - newWidth) / 2

        val padTop =
            (size - newHeight) / 2

        canvas.drawBitmap(
            resized,
            padLeft.toFloat(),
            padTop.toFloat(),
            null
        )

        resized.recycle()

        val pixels =
            IntArray(size * size)

        canvasBitmap.getPixels(
            pixels,
            0,
            size,
            0,
            0,
            size,
            size
        )

        val plane =
            size * size

        val data =
            FloatArray(
                3 * plane
            )

        for (i in pixels.indices) {

            val c = pixels[i]

            data[i] =
                ((c shr 16) and 0xff) / 255f

            data[plane + i] =
                ((c shr 8) and 0xff) / 255f

            data[2 * plane + i] =
                (c and 0xff) / 255f
        }

        canvasBitmap.recycle()

        return Prepared(
            data = data,
            scale = scale,
            padLeft = padLeft,
            padTop = padTop
        )
    }

    private fun decode(
        raw: Any?,
        prepared: Prepared,
        imageWidth: Int,
        imageHeight: Int,
        threshold: Float
    ): List<Detection> {

        val rows =
            mutableListOf<FloatArray>()

        fun collect(
            value: Any?
        ) {

            when (value) {

                is FloatArray -> {
                    if (value.size >= 22) {
                        rows += value
                    }
                }

                is Array<*> -> {
                    value.forEach {
                        collect(it)
                    }
                }
            }
        }

        collect(raw)

        if (rows.isEmpty()) {
            android.util.Log.d(
                "NudeBlurDetector",
                "No ONNX rows returned"
            )

            return emptyList()
        }

        /*
         * Model kann [1, 22, N] liefern.
         *
         * Dann erhalten wir 22 FloatArrays mit jeweils N Werten
         * und müssen sie in N Zeilen mit 22 Werten umwandeln.
         */
        val normalizedRows =
            if (
                rows.size == 22 &&
                rows.all {
                    it.size == rows[0].size
                }
            ) {

                val n =
                    rows[0].size

                List(n) { index ->

                    FloatArray(22) { classIndex ->
                        rows[classIndex][index]
                    }
                }

            } else {

                rows
            }

        val candidates =
            mutableListOf<Detection>()

        var highestScore = 0f
        var highestLabel = ""

        for (row in normalizedRows) {

            if (row.size < 22) {
                continue
            }

            var bestClass = -1
            var bestScore = 0f

            for (c in 0 until 18) {

                val score =
                    row[4 + c]

                if (score > bestScore) {
                    bestScore = score
                    bestClass = c
                }
            }

            if (bestScore > highestScore) {
                highestScore = bestScore

                highestLabel =
                    if (
                        bestClass in labels.indices
                    ) {
                        labels[bestClass]
                    } else {
                        "unknown"
                    }
            }

            if (
                bestClass < 0 ||
                bestScore < threshold
            ) {
                continue
            }

            val label =
                labels[bestClass]

            if (
                label !in censorClasses
            ) {
                continue
            }

            /*
             * Koordinaten des Modells befinden sich
             * im 320x320 Letterbox-Raum.
             *
             * Erst Padding entfernen,
             * anschließend durch den tatsächlichen
             * Resize-Faktor teilen.
             */
            val cx =
                row[0]

            val cy =
                row[1]

            val w =
                row[2]

            val h =
                row[3]

            val left =
                (
                    cx -
                        w / 2f -
                        prepared.padLeft
                    ) / prepared.scale

            val top =
                (
                    cy -
                        h / 2f -
                        prepared.padTop
                    ) / prepared.scale

            val right =
                (
                    cx +
                        w / 2f -
                        prepared.padLeft
                    ) / prepared.scale

            val bottom =
                (
                    cy +
                        h / 2f -
                        prepared.padTop
                    ) / prepared.scale

            val clampedLeft =
                left.coerceIn(
                    0f,
                    imageWidth.toFloat()
                )

            val clampedTop =
                top.coerceIn(
                    0f,
                    imageHeight.toFloat()
                )

            val clampedRight =
                right.coerceIn(
                    0f,
                    imageWidth.toFloat()
                )

            val clampedBottom =
                bottom.coerceIn(
                    0f,
                    imageHeight.toFloat()
                )

            if (
                clampedRight > clampedLeft &&
                clampedBottom > clampedTop
            ) {

                candidates += Detection(
                    RectF(
                        clampedLeft,
                        clampedTop,
                        clampedRight,
                        clampedBottom
                    ),
                    bestScore,
                    label
                )
            }
        }

        android.util.Log.d(
            "NudeBlurDetector",
            "rows=${normalizedRows.size}, " +
                "candidates=${candidates.size}, " +
                "highest=$highestScore " +
                "($highestLabel)"
        )

        return nms(
            candidates,
            0.45f
        )
    }

    private fun nms(
        items: List<Detection>,
        iouThreshold: Float
    ): List<Detection> {

        val remaining =
            items
                .sortedByDescending {
                    it.confidence
                }
                .toMutableList()

        val result =
            mutableListOf<Detection>()

        while (
            remaining.isNotEmpty()
        ) {

            val best =
                remaining.removeAt(0)

            result += best

            val toRemove =
                mutableListOf<Detection>()

            for (other in remaining) {

                if (
                    iou(
                        best.box,
                        other.box
                    ) >= iouThreshold
                ) {
                    toRemove += other
                }
            }

            remaining.removeAll(
                toRemove
            )
        }

        return result
    }

    private fun iou(
        a: RectF,
        b: RectF
    ): Float {

        val left =
            max(
                a.left,
                b.left
            )

        val top =
            max(
                a.top,
                b.top
            )

        val right =
            min(
                a.right,
                b.right
            )

        val bottom =
            min(
                a.bottom,
                b.bottom
            )

        val intersection =
            max(
                0f,
                right - left
            ) *
                max(
                    0f,
                    bottom - top
                )

        val union =
            a.width() *
                a.height() +
                b.width() *
                b.height() -
                intersection

        return if (
            union <= 0f
        ) {
            0f
        } else {
            intersection / union
        }
    }

    override fun close() {
        session.close()
        env.close()
    }

    private data class Prepared(
        val data: FloatArray,
        val scale: Float,
        val padLeft: Int,
        val padTop: Int
    )
}
