package com.osmarlg.teleprompter

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
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

    /** Sube cada vez que un archivo termina de subir (para recargar listas y piezas). */
    var uploadsVersion by mutableIntStateOf(0)

    /** La notificación de subidas pide abrir «Subidas». */
    var openUploads by mutableIntStateOf(0)
}

/** Pantallas de la app (una pila sencilla; «atrás» regresa a la anterior). */
sealed interface Screen {
    data object Scripts : Screen
    data object Uploads : Screen
    data class Record(val guion: Guion, val index: Int = 0) : Screen
    data class Piece(val guion: Guion) : Screen
    data class Viewer(val archivo: Archivo) : Screen
}

class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_UPLOADS = "abrir_subidas"
    }

    private lateinit var prefs: Prefs

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        prefs = Prefs(this)
        Crm.appVersion = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull().orEmpty()
        Uploader.init(this)
        Uploader.resumePending(this)
        handlePairingLink(intent)
        handleUploadsIntent(intent)
        // La notificación «Subiendo…» necesita permiso desde Android 13.
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFFF43F5E),
                    background = Color(0xFF0B0F17),
                    surface = Color(0xFF141A24),
                )
            ) {
                val stack = remember { mutableStateListOf<Screen>(Screen.Scripts) }
                fun push(screen: Screen) { stack.add(screen) }
                fun back() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }

                LaunchedEffect(AppEvents.openUploads) {
                    if (AppEvents.openUploads > 0 && stack.last() != Screen.Uploads) push(Screen.Uploads)
                }

                if (stack.size > 1) BackHandler { back() }

                when (val current = stack.last()) {
                    Screen.Scripts -> ScriptsScreen(
                        prefs = prefs,
                        onRecord = { push(Screen.Record(it)) },
                        onPiece = { push(Screen.Piece(it)) },
                        onUploads = { push(Screen.Uploads) },
                    )
                    Screen.Uploads -> UploadsScreen(onBack = { back() })
                    is Screen.Piece -> PieceScreen(
                        initial = current.guion,
                        prefs = prefs,
                        onBack = { back() },
                        onRecord = { guion, index -> push(Screen.Record(guion, index)) },
                        onView = { push(Screen.Viewer(it)) },
                    )
                    is Screen.Viewer -> MediaViewer(current.archivo, onBack = { back() })
                    is Screen.Record -> PermissionGate(
                        permissions = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO),
                        onCancel = { back() },
                    ) { audioGranted ->
                        key(current) {
                            RecorderScreen(
                                guion = current.guion,
                                prefs = prefs,
                                audioGranted = audioGranted,
                                startIndex = current.index,
                                onClose = { back() },
                                onReview = { i ->
                                    // Al regresar, vuelve a la misma toma.
                                    stack[stack.lastIndex] = Screen.Record(current.guion, i)
                                    push(Screen.Piece(current.guion))
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handlePairingLink(intent)
        handleUploadsIntent(intent)
    }

    private fun handleUploadsIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_UPLOADS, false) == true) {
            intent.removeExtra(EXTRA_UPLOADS)
            AppEvents.openUploads++
        }
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
                Uploader.resumePending(this@MainActivity)
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
