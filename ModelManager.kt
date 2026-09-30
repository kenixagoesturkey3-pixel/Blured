package com.example.nudeblurguard

import android.content.Context
import java.io.File
import java.security.MessageDigest

/**
 * Offline-only model installation.
 *
 * The ONNX model is bundled into the APK as an asset by the build workflow.
 * No network access is performed by the app at runtime.
 */
object ModelManager {

    private const val MODEL_NAME = "nudity_detector.onnx"
    private const val EXPECTED_SHA256 =
        "776773dd37d99aa2ffe63bec9f92a835d82f3e40d627ffb47d9c3455aec600aa"

    fun modelFile(context: Context): File =
        File(context.filesDir, MODEL_NAME)

    fun isInstalled(context: Context): Boolean {
        val file = modelFile(context)
        return file.exists() &&
            file.length() > 1_000_000L &&
            sha256(file) == EXPECTED_SHA256
    }

    @Throws(Exception::class)
    fun ensureInstalled(
        context: Context,
        progress: (Int) -> Unit = {}
    ): File {
        val target = modelFile(context)

        if (isInstalled(context)) {
            progress(100)
            return target
        }

        val tmp = File(context.cacheDir, "$MODEL_NAME.part")
        tmp.delete()

        context.assets.open(MODEL_NAME).use { input ->
            tmp.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
            }
        }

        if (sha256(tmp) != EXPECTED_SHA256) {
            tmp.delete()
            error("Bundled model SHA-256 verification failed")
        }

        if (target.exists()) {
            target.delete()
        }

        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }

        progress(100)
        return target
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")

        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)

            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }

        return digest.digest().joinToString("") {
            "%02x".format(it)
        }
    }
}
