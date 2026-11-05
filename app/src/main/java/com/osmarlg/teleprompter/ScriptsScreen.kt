package com.osmarlg.teleprompter

import android.content.Context
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.launch

private val Muted = Color(0xFF9CA3AF)
private val Warn = Color(0xFFFBBF24)

@Composable
fun ScriptsScreen(prefs: Prefs, onOpen: (Guion) -> Unit) {
    val scope = rememberCoroutineScope()
    var guiones by remember { mutableStateOf<List<Guion>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var reloadTick by remember { mutableIntStateOf(0) }
    var modeFilter by remember { mutableStateOf<String?>(null) }
    var showAll by remember { mutableStateOf(prefs.showAll) }
    var canSeeAll by remember { mutableStateOf(false) }
    var showAccounts by remember { mutableStateOf(false) }
    val sessionVersion = AppEvents.sessionVersion

    fun fromCacheOrDemo(): List<Guion> =
        runCatching { GuionParser.parse(prefs.cache) }.getOrNull()?.takeIf { it.isNotEmpty() }
            ?: GuionParser.parse(DEMO_JSON)

    LaunchedEffect(reloadTick, sessionVersion, showAll) {
        val url: String
        val token: String
        when {
            prefs.signedIn -> {
                url = Crm.guionesUrl(prefs.crmUrl) + if (showAll) "?todos=1" else ""
                token = prefs.crmToken
            }
            prefs.apiUrl.isNotBlank() -> { url = Api.guionesUrl(prefs.apiUrl); token = prefs.apiToken }
            else -> {
                guiones = GuionParser.parse(DEMO_JSON)
                message = "Modo demo. Inicia sesión con tu usuario del CRM en Ajustes."
                return@LaunchedEffect
            }
        }
        loading = true
        message = null
        try {
            val raw = Api.fetch(url, token)
            val parsed = GuionParser.parse(raw)
            canSeeAll = prefs.signedIn && runCatching { org.json.JSONObject(raw).optBoolean("puede_ver_todos", false) }.getOrDefault(false)
            prefs.cache = raw
            guiones = parsed
            if (parsed.isEmpty()) {
                message = when {
                    !prefs.signedIn -> "La API no regresó guiones con tomas."
                    showAll -> "No hay guiones pendientes por grabar."
                    else -> "No tienes guiones pendientes por grabar."
                }
            }
        } catch (e: Crm.CrmException) {
            if (e.status == 401 && prefs.signedIn) {
                prefs.signOut()
                guiones = GuionParser.parse(DEMO_JSON)
                message = "La sesión se cerró. Vuelve a iniciar sesión en Ajustes."
            } else {
                guiones = fromCacheOrDemo()
                message = "${e.message} Mostrando lo último guardado."
            }
        } catch (e: Exception) {
            guiones = fromCacheOrDemo()
            message = "Sin conexión (${e.message}). Mostrando lo último guardado."
        } finally {
            loading = false
        }
    }

    val modes = guiones.mapNotNull { g -> g.modo?.let { it to (g.modoLabel ?: it) } }.distinct()
    val visible = guiones.filter { modeFilter == null || it.modo == modeFilter }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Guiones", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    if (prefs.signedIn && prefs.crmUser.isNotBlank()) {
                        Text(
                            "${prefs.crmUser} ▾",
                            fontSize = 13.sp, color = Color(0xFF60A5FA),
                            modifier = Modifier.clickable { showAccounts = true },
                        )
                    }
                }
                TextButton(onClick = { reloadTick++ }) { Text("⟳ Recargar") }
                TextButton(onClick = { showSettings = true }) { Text("⚙ Ajustes") }
            }

            message?.let {
                Text(it, color = Warn, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }

            if (modes.size > 1 || canSeeAll) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (canSeeAll) {
                        FilterChip(
                            selected = !showAll,
                            onClick = { showAll = false; prefs.showAll = false },
                            label = { Text("Míos") },
                        )
                        FilterChip(
                            selected = showAll,
                            onClick = { showAll = true; prefs.showAll = true },
                            label = { Text("De todos") },
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    FilterChip(selected = modeFilter == null, onClick = { modeFilter = null }, label = { Text("Todo") })
                    modes.forEach { (mode, label) ->
                        FilterChip(selected = modeFilter == mode, onClick = { modeFilter = mode }, label = { Text(label) })
                    }
                }
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
                items(visible, key = { it.id }) { g -> ScriptCard(g) { onOpen(g) } }
            }
        }
    }

    if (showSettings) {
        SettingsDialog(prefs = prefs, onDismiss = {
            showSettings = false
            scope.launch { reloadTick++ }
        })
    }

    if (showAccounts) {
        AccountsDialog(
            prefs = prefs,
            onAdd = { showAccounts = false; showSettings = true },
            onDismiss = { showAccounts = false },
        )
    }
}

/** Cambiar de cuenta con un toque entre las que ya entraron en este teléfono. */
@Composable
private fun AccountsDialog(prefs: Prefs, onAdd: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Cuentas") },
        text = {
            Column {
                prefs.accounts.forEach { account ->
                    val active = account.token == prefs.crmToken
                    Row(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable {
                                if (!active) {
                                    prefs.use(account)
                                    AppEvents.sessionVersion++
                                }
                                onDismiss()
                            }
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(account.name.ifBlank { account.email }, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            Text(account.email, fontSize = 12.sp, color = Muted)
                        }
                        if (active) Text("✓", color = Color(0xFF34D399), fontSize = 18.sp)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onAdd) { Text("Agregar otra cuenta") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } },
    )
}

@Composable
private fun ScriptCard(g: Guion, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    g.titulo,
                    fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color.White,
                    modifier = Modifier.weight(1f),
                )
                if (g.mio) {
                    Text(
                        "Tuyo",
                        fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0B0F17),
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(Color(0xFF34D399))
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
            val meta = listOfNotNull(g.cliente, g.modoLabel, g.fecha).joinToString(" · ")
            if (meta.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(meta, fontSize = 13.sp, color = Color(0xFF60A5FA))
            }
            if (!g.mio && !g.graba.isNullOrBlank()) {
                Text("Lo graba ${g.graba}", fontSize = 13.sp, color = Warn)
            }
            Spacer(Modifier.height(4.dp))
            val dur = if (g.duracionTotal > 0) " · ~${g.duracionTotal} s" else ""
            val done = if (g.piezaId != null) " · ${g.hechas}/${g.tomas.size} hechas" else ""
            Text("${g.tomas.size} tomas$done$dur", fontSize = 13.sp, color = Muted)
            Spacer(Modifier.height(6.dp))
            val first = g.tomas.first()
            Text(
                first.resumen,
                fontSize = 14.sp, color = Color(0xFFD1D5DB),
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Escanea el QR de «Vincular la app» del perfil del CRM con el lector de Google (no pide permiso de cámara). */
fun scanPairingQr(context: Context, onResult: (Crm.Pairing?) -> Unit) {
    val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
    GmsBarcodeScanning.getClient(context, options).startScan()
        .addOnSuccessListener { barcode -> onResult(Crm.parsePairing(barcode.rawValue)) }
        .addOnFailureListener { onResult(null) }
}

@Composable
private fun SettingsDialog(prefs: Prefs, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var signedIn by remember { mutableStateOf(prefs.signedIn) }
    var email by remember { mutableStateOf(prefs.crmEmail) }
    var password by remember { mutableStateOf("") }
    var crmUrl by remember { mutableStateOf(prefs.crmUrl) }
    var busy by remember { mutableStateOf(false) }
    var authError by remember { mutableStateOf<String?>(null) }
    var showAdvanced by remember { mutableStateOf(false) }

    var url by remember { mutableStateOf(prefs.apiUrl) }
    var token by remember { mutableStateOf(prefs.apiToken) }
    var countdown by remember { mutableIntStateOf(prefs.countdown) }
    var autoStop by remember { mutableStateOf(prefs.autoStop) }
    var autoAdvance by remember { mutableStateOf(prefs.autoAdvance) }
    var autoUpload by remember { mutableStateOf(prefs.autoUpload) }
    var keepLocal by remember { mutableStateOf(prefs.keepLocal) }
    var volumeMode by remember { mutableStateOf(prefs.volumeMode) }

    fun signedInWith(serverUrl: String, session: Crm.Session) {
        prefs.signIn(serverUrl, session)
        signedIn = true
        password = ""
        authError = null
        AppEvents.sessionVersion++
        Uploader.resumePending(context, prefs)
        Toast.makeText(context, "Sesión iniciada: ${session.userName}", Toast.LENGTH_SHORT).show()
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Ajustes") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Cuenta del CRM", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                if (signedIn) {
                    Text("${prefs.crmUser} · ${prefs.crmEmail}", fontSize = 14.sp)
                    Text("Tus guiones salen de Grabaciones y cada toma se sube sola.", fontSize = 12.sp, color = Muted)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(enabled = !busy, onClick = {
                            busy = true
                            scope.launch {
                                Crm.logout(prefs.crmUrl, prefs.crmToken)
                                prefs.signOut()
                                signedIn = prefs.signedIn
                                busy = false
                                AppEvents.sessionVersion++
                            }
                        }) { Text("Cerrar sesión") }
                        TextButton(enabled = !busy, onClick = { signedIn = false; email = "" }) { Text("Agregar otra cuenta") }
                    }
                } else {
                    OutlinedTextField(
                        value = email, onValueChange = { email = it },
                        label = { Text("Correo") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = password, onValueChange = { password = it },
                        label = { Text("Contraseña") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    authError?.let { Text(it, color = Color(0xFFF87171), fontSize = 12.sp) }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = !busy && email.isNotBlank() && password.isNotBlank(), onClick = {
                            busy = true
                            authError = null
                            scope.launch {
                                try {
                                    signedInWith(Crm.base(crmUrl), Crm.login(crmUrl, email, password))
                                } catch (e: Exception) {
                                    authError = e.message ?: "No se pudo iniciar sesión."
                                } finally {
                                    busy = false
                                }
                            }
                        }) { Text(if (busy) "Entrando…" else "Entrar") }
                        OutlinedButton(enabled = !busy, onClick = {
                            scanPairingQr(context) { pairing ->
                                if (pairing == null) {
                                    authError = "No se leyó un QR del CRM. En tu perfil del CRM toca «Vincular la app con QR»."
                                    return@scanPairingQr
                                }
                                busy = true
                                scope.launch {
                                    try {
                                        signedInWith(Crm.base(pairing.url), Crm.pair(pairing))
                                    } catch (e: Exception) {
                                        authError = e.message ?: "No se pudo vincular."
                                    } finally {
                                        busy = false
                                    }
                                }
                            }
                        }) { Text("Vincular con QR") }
                    }
                }

                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))

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
                SwitchRow("Subir cada toma al terminar", autoUpload) { autoUpload = it }
                SwitchRow("Conservar la copia en el celular después de subirla", keepLocal) { keepLocal = it }

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
                    fontSize = 12.sp, color = Muted,
                )

                Spacer(Modifier.height(12.dp))
                TextButton(onClick = { showAdvanced = !showAdvanced }) {
                    Text(if (showAdvanced) "Ocultar avanzado" else "Avanzado")
                }
                if (showAdvanced) {
                    OutlinedTextField(
                        value = crmUrl, onValueChange = { crmUrl = it },
                        label = { Text("Servidor del CRM") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("API propia (solo sin sesión del CRM)", fontSize = 12.sp, color = Muted)
                    OutlinedTextField(
                        value = url, onValueChange = { url = it },
                        label = { Text("URL base de la API") },
                        placeholder = { Text("https://mi-api.com/api") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = token, onValueChange = { token = it },
                        label = { Text("Token (opcional)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            Button(enabled = !busy, onClick = {
                if (!prefs.signedIn) prefs.crmUrl = crmUrl
                prefs.apiUrl = url
                prefs.apiToken = token
                prefs.countdown = countdown
                prefs.autoStop = autoStop
                prefs.autoAdvance = autoAdvance
                prefs.autoUpload = autoUpload
                prefs.keepLocal = keepLocal
                prefs.volumeMode = volumeMode
                onDismiss()
            }) { Text("Guardar") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancelar") } },
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
