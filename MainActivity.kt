package com.example.nudeblurguard

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private val prefs by lazy {
        getSharedPreferences("settings", MODE_PRIVATE)
    }

    private var threshold by mutableFloatStateOf(0.50f)
    private var fps by mutableIntStateOf(10)
    private var pixelSize by mutableIntStateOf(28)
    private var filterEnabled by mutableStateOf(false)

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                filterEnabled = true

                val serviceIntent = Intent(this, ScreenFilterService::class.java).apply {
                    action = ScreenFilterService.ACTION_START
                    putExtra(ScreenFilterService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(ScreenFilterService.EXTRA_DATA, result.data)
                    putExtra(ScreenFilterService.EXTRA_THRESHOLD, threshold)
                    putExtra(ScreenFilterService.EXTRA_FPS, fps)
                    putExtra(ScreenFilterService.EXTRA_PIXEL_SIZE, pixelSize)
                }

                ContextCompat.startForegroundService(this, serviceIntent)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        threshold = prefs.getFloat("threshold", 0.50f)
        fps = prefs.getInt("fps", 10)
        pixelSize = prefs.getInt("pixelSize", 28)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp)
                    ) {
                        Text(
                            "NudeBlur Guard",
                            style = MaterialTheme.typography.headlineMedium
                        )

                        Text(
                            if (filterEnabled)
                                "● Filter aktiv"
                            else
                                "○ Filter ausgeschaltet",
                            style = MaterialTheme.typography.titleMedium
                        )

                        HorizontalDivider()

                        Text("Erkennungssicherheit: ${(threshold * 100).toInt()} %")
                        Slider(
                            value = threshold,
                            onValueChange = {
                                threshold = it
                                save()
                            },
                            valueRange = 0.35f..0.90f
                        )

                        Text("Analysegeschwindigkeit: $fps FPS")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(5, 8, 10, 12, 15).forEach { value ->
                                FilterChip(
                                    selected = fps == value,
                                    onClick = {
                                        fps = value
                                        save()
                                    },
                                    label = { Text("$value") }
                                )
                            }
                        }

                        Text("Pixelgröße: $pixelSize")
                        Slider(
                            value = pixelSize.toFloat(),
                            onValueChange = {
                                pixelSize = it.toInt()
                                save()
                            },
                            valueRange = 8f..60f
                        )

                        Spacer(Modifier.height(4.dp))

                        Button(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { startFilter() },
                            enabled = !filterEnabled
                        ) {
                            Text("Filter starten")
                        }

                        OutlinedButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { stopFilter() },
                            enabled = filterEnabled
                        ) {
                            Text("Filter stoppen")
                        }

                        Text(
                            "Alle Einstellungen gelten lokal. Die Bildschirmframes " +
                                "werden für die Erkennung auf dem Gerät verarbeitet."
                        )

                        Text(
                            "Hinweis: Geschützte Android-Fenster können nicht erfasst werden."
                        )
                    }
                }
            }
        }
    }

    private fun startFilter() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
            return
        }

        val manager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }

    private fun stopFilter() {
        filterEnabled = false

        startService(
            Intent(this, ScreenFilterService::class.java)
                .setAction(ScreenFilterService.ACTION_STOP)
        )
    }

    private fun save() {
        prefs.edit()
            .putFloat("threshold", threshold)
            .putInt("fps", fps)
            .putInt("pixelSize", pixelSize)
            .apply()
    }
}