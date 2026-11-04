package com.osmarlg.teleprompter

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

enum class RemoteKey { UP, DOWN }

/** Puente para los botones de volumen / control Bluetooth de selfie. */
object KeyBus {
    private val _events = MutableSharedFlow<RemoteKey>(extraBufferCapacity = 8)
    val events: SharedFlow<RemoteKey> = _events
    var listening = false
    fun emit(k: RemoteKey) = _events.tryEmit(k)
}

/** Avisa a las pantallas que la sesión del CRM cambió (para recargar los guiones). */
object AppEvents {
    var sessionVersion by mutableIntStateOf(0)
}

class MainActivity : ComponentActivity() {

    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        prefs = Prefs(this)
        Crm.appVersion = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull().orEmpty()
        Uploader.resumePending(this, prefs)
        handlePairingLink(intent)

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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handlePairingLink(intent)
    }

    /** El QR del perfil también se puede escanear con la cámara del teléfono: abre la app y la vincula. */
    private fun handlePairingLink(intent: Intent?) {
        val pairing = Crm.parsePairing(intent?.dataString) ?: return
        intent?.data = null
        lifecycleScope.launch {
            try {
                val session = Crm.pair(pairing)
                prefs.signIn(Crm.base(pairing.url), session)
                AppEvents.sessionVersion++
                Uploader.resumePending(this@MainActivity, prefs)
                Toast.makeText(this@MainActivity, "App vinculada: ${session.userName}", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, e.message ?: "No se pudo vincular.", Toast.LENGTH_LONG).show()
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
