package com.example.nudeblurguard

import android.graphics.RectF

data class Detection(
    val box: RectF,
    val confidence: Float,
    val label: String
)

interface NudityDetector {
    fun detect(bitmap: android.graphics.Bitmap, threshold: Float): List<Detection>
    fun close()
}