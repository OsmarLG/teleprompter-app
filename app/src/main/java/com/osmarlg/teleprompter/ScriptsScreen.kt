package com.osmarlg.teleprompter

import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch

@Composable
fun ScriptsScreen(prefs: Prefs, onOpen: (Guion) -> Unit) {
    val scope = rememberCoroutineScope()
    var guiones by remember { mutableStateOf<List<Guion>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var reloadTick by remember { mutableIntStateOf(0) }

    fun fromCacheOrDemo(): List<Guion> =
        runCatching { GuionParser.parse(prefs.cache) }.getOrNull()?.takeIf { it.isNotEmpty() }
            ?: GuionParser.parse(DEMO_JSON)

    LaunchedEffect(reloadTick) {
        if (prefs.apiUrl.isBlank()) {
            guiones = GuionParser.parse(DEMO_JSON)
            message = "Modo demo. Pon la URL de tu API en Ajustes."
            return@LaunchedEffect
        }
        loading = true
        message = null
        try {
            val raw = Api.fetch(Api.guionesUrl(prefs.apiUrl), prefs.apiToken)
            val parsed = GuionParser.parse(raw)
            prefs.cache = raw
            guiones = parsed
            if (parsed.isEmpty()) message = "La API no regresó guiones con tomas."
        } catch (e: Exception) {
            guiones = fromCacheOrDemo()
            message = "No se pudo cargar la API (${e.message}). Mostrando lo último guardado."
        } finally {
            loading = false
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Guiones", fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = { reloadTick++ }) { Text("⟳ Recargar") }
                TextButton(onClick = { showSettings = true }) { Text("⚙ Ajustes") }
            }

            message?.let {
                Text(
                    it,
                    color = Color(0xFFFBBF24),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            if (loading) {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }

            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(guiones, key = { it.id }) { g ->
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable { onOpen(g) },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(g.titulo, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                            Spacer(Modifier.height(4.dp))
                            val dur = if (g.duracionTotal > 0) " · ~${g.duracionTotal} s" else ""
                            Text("${g.tomas.size} tomas$dur", fontSize = 13.sp, color = Color(0xFF9CA3AF))
                            Spacer(Modifier.height(6.dp))
                            Text(
                                g.tomas.first().texto,
                                fontSize = 14.sp,
                                color = Color(0xFFD1D5DB),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }

    if (showSettings) {
        SettingsDialog(prefs = prefs, onDismiss = {
            showSettings = false
            scope.launch { reloadTick++ }
        })
    }
}

@Composable
private fun SettingsDialog(prefs: Prefs, onDismiss: () -> Unit) {
    var url by remember { mutableStateOf(prefs.apiUrl) }
    var token by remember { mutableStateOf(prefs.apiToken) }
    var countdown by remember { mutableIntStateOf(prefs.countdown) }
    var autoStop by remember { mutableStateOf(prefs.autoStop) }
    var autoAdvance by remember { mutableStateOf(prefs.autoAdvance) }
    var autoUpload by remember { mutableStateOf(prefs.autoUpload) }
    var volumeMode by remember { mutableStateOf(prefs.volumeMode) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ajustes") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = url, onValueChange = { url = it },
                    label = { Text("URL base de la API") },
                    placeholder = { Text("https://mi-api.com/api") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = token, onValueChange = { token = it },
                    label = { Text("Token (opcional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))

                Text("Cuenta regresiva", fontSize = 14.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0, 3, 5).forEach { s ->
                        FilterChip(
                            selected = countdown == s,
                            onClick = { countdown = s },
                            label = { Text(if (s == 0) "Sin" else "$s s") },
                        )
                    }
                }
                SwitchRow("Detener al terminar la duración", autoStop) { autoStop = it }
                SwitchRow("Pasar a la siguiente toma al terminar", autoAdvance) { autoAdvance = it }
                SwitchRow("Subir cada toma a la API", autoUpload) { autoUpload = it }

                Spacer(Modifier.height(8.dp))
                Text("Botones de volumen / control Bluetooth", fontSize = 14.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = volumeMode == VolumeMode.NAVEGAR,
                        onClick = { volumeMode = VolumeMode.NAVEGAR },
                        label = { Text("Cambiar toma") },
                    )
                    FilterChip(
                        selected = volumeMode == VolumeMode.GRABAR,
                        onClick = { volumeMode = VolumeMode.GRABAR },
                        label = { Text("Grabar/Detener") },
                    )
                }
                Text(
                    if (volumeMode == VolumeMode.NAVEGAR) "Vol + siguiente · Vol − anterior"
                    else "Vol + grabar/detener · Vol − siguiente",
                    fontSize = 12.sp, color = Color(0xFF9CA3AF),
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                prefs.apiUrl = url
                prefs.apiToken = token
                prefs.countdown = countdown
                prefs.autoStop = autoStop
                prefs.autoAdvance = autoAdvance
                prefs.autoUpload = autoUpload
                prefs.volumeMode = volumeMode
                onDismiss()
            }) { Text("Guardar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** Pide cámara (obligatoria) y micrófono (opcional) antes de mostrar el contenido. */
@Composable
fun PermissionGate(
    permissions: List<String>,
    onCancel: () -> Unit,
    content: @Composable (audioGranted: Boolean) -> Unit,
) {
    val context = LocalContext.current
    fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

    var camera by remember { mutableStateOf(granted(android.Manifest.permission.CAMERA)) }
    var audio by remember { mutableStateOf(granted(android.Manifest.permission.RECORD_AUDIO)) }
    var asked by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        camera = res[android.Manifest.permission.CAMERA] ?: granted(android.Manifest.permission.CAMERA)
        audio = res[android.Manifest.permission.RECORD_AUDIO] ?: granted(android.Manifest.permission.RECORD_AUDIO)
        asked = true
    }

    LaunchedEffect(Unit) {
        if (!camera || !audio) launcher.launch(permissions.toTypedArray())
    }

    when {
        camera -> content(audio)
        !asked -> Box(Modifier.fillMaxSize())
        else -> Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                Modifier.fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("La app necesita la cámara para grabar.", color = Color.White, fontSize = 16.sp)
                Spacer(Modifier.height(16.dp))
                Button(onClick = { launcher.launch(permissions.toTypedArray()) }) { Text("Dar permiso") }
                TextButton(onClick = onCancel) { Text("Regresar") }
            }
        }
    }
}
