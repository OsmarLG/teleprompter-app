package com.osmarlg.teleprompter

import android.Manifest
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

enum class RemoteKey { UP, DOWN }

/** Puente para los botones de volumen / control Bluetooth de selfie. */
object KeyBus {
    private val _events = MutableSharedFlow<RemoteKey>(extraBufferCapacity = 8)
    val events: SharedFlow<RemoteKey> = _events
    var listening = false
    fun emit(k: RemoteKey) = _events.tryEmit(k)
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val prefs = Prefs(this)

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFFF43F5E),
                    background = Color(0xFF0B0F17),
                    surface = Color(0xFF141A24),
                )
            ) {
                var selected by remember { mutableStateOf<Guion?>(null) }

                val current = selected
                if (current == null) {
                    ScriptsScreen(prefs = prefs, onOpen = { selected = it })
                } else {
                    BackHandler { selected = null }
                    PermissionGate(
                        permissions = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO),
                        onCancel = { selected = null },
                    ) { audioGranted ->
                        RecorderScreen(
                            guion = current,
                            prefs = prefs,
                            audioGranted = audioGranted,
                            onClose = { selected = null },
                        )
                    }
                }
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (KeyBus.listening) {
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> { if (event?.repeatCount == 0) KeyBus.emit(RemoteKey.UP); return true }
                KeyEvent.KEYCODE_VOLUME_DOWN -> { if (event?.repeatCount == 0) KeyBus.emit(RemoteKey.DOWN); return true }
            }
        }
        return super.onKeyDown(keyCode, event)
    }
}
