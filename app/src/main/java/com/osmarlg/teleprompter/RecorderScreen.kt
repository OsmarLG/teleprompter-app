package com.osmarlg.teleprompter

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import android.view.WindowManager
import android.widget.Toast
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

private val Accent = Color(0xFFF43F5E)
private val Soft = Color(0xFFD1D5DB)
private val Dim = Color(0xFF9CA3AF)
private val Ok = Color(0xFF34D399)
private val Amber = Color(0xFFFBBF24)
private val Panel = Color.Black.copy(alpha = 0.55f)

private suspend fun Context.cameraProvider(): ProcessCameraProvider = suspendCoroutine { cont ->
    val future = ProcessCameraProvider.getInstance(this)
    future.addListener({ cont.resume(future.get()) }, ContextCompat.getMainExecutor(this))
}

private fun slug(s: String) = s.lowercase(Locale.ROOT)
    .replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40).ifBlank { "guion" }

@SuppressLint("MissingPermission")
@Composable
fun RecorderScreen(
    guion: Guion,
    prefs: Prefs,
    audioGranted: Boolean,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val screenHeight = LocalConfiguration.current.screenHeightDp

    var index by remember { mutableIntStateOf(0) }
    var front by remember { mutableStateOf(prefs.frontCamera) }
    var fontSize by remember { mutableFloatStateOf(prefs.fontSize) }
    var autoStop by remember { mutableStateOf(prefs.autoStop) }

    var recording by remember { mutableStateOf<Recording?>(null) }
    var countdown by remember { mutableStateOf<Int?>(null) }
    var elapsed by remember { mutableIntStateOf(0) }
    var pendingJob by remember { mutableStateOf<Job?>(null) }
    var capturing by remember { mutableStateOf(false) }
    val recorded = remember { mutableStateMapOf<Int, Boolean>() }
    val photosTaken = remember { mutableStateMapOf<Int, Int>() }
    /** Indicaciones abiertas por toma; sin texto a decir se abren solas. */
    val showInfo = remember { mutableStateMapOf<Int, Boolean>() }

    val toma = guion.tomas[index]
    val isPhoto = toma.esFoto
    val isBusy = recording != null || countdown != null || capturing
    val infoOpen = showInfo[index] ?: toma.texto.isBlank()

    // Pantalla siempre encendida mientras grabas
    DisposableEffect(Unit) {
        val window = (context as? Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        KeyBus.listening = true
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            KeyBus.listening = false
        }
    }

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    val videoCapture = remember {
        val recorder = Recorder.Builder()
            .setQualitySelector(
                QualitySelector.from(Quality.FHD, FallbackStrategy.lowerQualityOrHigherThan(Quality.HD))
            )
            .build()
        VideoCapture.withOutput(recorder)
    }
    val imageCapture = remember {
        ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build()
    }

    // Una toma de foto usa la cámara de fotos; una de video, la de video.
    LaunchedEffect(front, isPhoto) {
        val provider = context.cameraProvider()
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        try {
            provider.unbindAll()
            if (isPhoto) {
                provider.bindToLifecycle(lifecycleOwner, selector, preview, imageCapture)
            } else {
                provider.bindToLifecycle(lifecycleOwner, selector, preview, videoCapture)
            }
        } catch (e: Exception) {
            Toast.makeText(context, "No se pudo abrir la cámara: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            recording?.stop()
            ProcessCameraProvider.getInstance(context).addListener(
                { runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() } },
                ContextCompat.getMainExecutor(context),
            )
        }
    }

    // Cronómetro de la toma
    LaunchedEffect(recording) {
        elapsed = 0
        while (recording != null) {
            delay(1000)
            elapsed++
        }
    }

    fun goTo(i: Int) {
        if (isBusy) return
        index = i.coerceIn(0, guion.tomas.lastIndex)
    }

    fun stop() {
        pendingJob?.cancel()
        pendingJob = null
        if (countdown != null) {
            countdown = null
            return
        }
        recording?.stop()
    }

    fun fileBase(current: Toma): String {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date())
        return "${slug(guion.titulo)}_toma${current.orden.toString().padStart(2, '0')}_$ts"
    }

    fun startRecording() {
        val current = guion.tomas[index]
        val currentIndex = index
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, fileBase(current))
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Teleprompter/${slug(guion.titulo)}")
            }
        }
        val options = MediaStoreOutputOptions.Builder(
            context.contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(values).build()

        var pending = videoCapture.output.prepareRecording(context, options)
        if (audioGranted) pending = pending.withAudioEnabled()

        recording = pending.start(ContextCompat.getMainExecutor(context)) { event ->
            if (event is VideoRecordEvent.Finalize) {
                recording = null
                pendingJob?.cancel()
                pendingJob = null
                if (!event.hasError()) {
                    recorded[currentIndex] = true
                    if (prefs.autoUpload) {
                        Uploader.upload(context, prefs, guion, current, event.outputResults.outputUri)
                    }
                    if (prefs.autoAdvance && currentIndex < guion.tomas.lastIndex) {
                        index = currentIndex + 1
                    }
                } else {
                    Toast.makeText(context, "Error al grabar (${event.error})", Toast.LENGTH_SHORT).show()
                }
            }
        }

        if (autoStop && current.duracion > 0) {
            pendingJob = scope.launch {
                delay(current.duracion * 1000L + 400)
                recording?.stop()
            }
        }
    }

    fun takePhoto() {
        val current = guion.tomas[index]
        val currentIndex = index
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileBase(current))
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Teleprompter/${slug(guion.titulo)}")
            }
        }
        val output = ImageCapture.OutputFileOptions.Builder(
            context.contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
        ).build()

        capturing = true
        imageCapture.takePicture(
            output,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                    capturing = false
                    val uri = result.savedUri ?: return
                    val taken = (photosTaken[currentIndex] ?: 0) + 1
                    photosTaken[currentIndex] = taken
                    recorded[currentIndex] = true
                    if (prefs.autoUpload) Uploader.upload(context, prefs, guion, current, uri)
                    if (prefs.autoAdvance && current.fotos > 0 && taken >= current.fotos && currentIndex < guion.tomas.lastIndex) {
                        index = currentIndex + 1
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    capturing = false
                    Toast.makeText(context, "No se pudo tomar la foto (${exception.message})", Toast.LENGTH_SHORT).show()
                }
            },
        )
    }

    fun toggleRecord() {
        if (recording != null || countdown != null) { stop(); return }
        if (capturing) return
        val photo = guion.tomas[index].esFoto
        pendingJob = scope.launch {
            for (i in prefs.countdown downTo 1) {
                countdown = i
                delay(1000)
            }
            countdown = null
            if (photo) takePhoto() else startRecording()
        }
    }

    // Botones de volumen / control Bluetooth
    val onKey by rememberUpdatedState<(RemoteKey) -> Unit> { key ->
        when (prefs.volumeMode) {
            VolumeMode.NAVEGAR -> if (key == RemoteKey.UP) goTo(index + 1) else goTo(index - 1)
            VolumeMode.GRABAR -> if (key == RemoteKey.UP) toggleRecord() else goTo(index + 1)
        }
    }
    LaunchedEffect(Unit) { KeyBus.events.collect { onKey(it) } }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

        Column(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 10.dp, vertical = 6.dp)
                .pointerInput(isBusy) {
                    var total = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { total = 0f },
                        onDragEnd = {
                            if (total < -80) goTo(index + 1) else if (total > 80) goTo(index - 1)
                        },
                    ) { _, drag -> total += drag }
                },
        ) {
            // ---------- Encabezado compacto ----------
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Panel)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("TOMA ${index + 1}/${guion.tomas.size}", color = Accent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                if (isPhoto) {
                    val goal = if (toma.fotos > 0) "/${toma.fotos}" else ""
                    Chip("📷 ${photosTaken[index] ?: 0}$goal fotos", Color(0xFF60A5FA))
                } else if (toma.duracion > 0) {
                    Chip("${toma.duracion} s", Soft)
                }
                toma.decir?.let { Chip(it, if (toma.texto.isBlank()) Amber else Soft) }
                UploadChip(guion, toma, done = recorded[index] == true || toma.hecha)
                Spacer(Modifier.weight(1f))
                Text(
                    if (infoOpen) "Ocultar ▴" else "Indicaciones ▾",
                    color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.15f))
                        .clickable { showInfo[index] = !infoOpen }
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }

            // ---------- Lo que se dice: grande y libre ----------
            if (toma.texto.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Panel)
                        .heightIn(max = (screenHeight * (if (infoOpen) 0.26f else 0.42f)).dp)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Text(
                        toma.texto,
                        color = Color.White,
                        fontSize = fontSize.sp,
                        lineHeight = (fontSize * 1.25f).sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // ---------- Indicaciones: ordenadas y plegables ----------
            if (infoOpen) {
                Spacer(Modifier.height(6.dp))
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Panel)
                        .heightIn(max = (screenHeight * (if (toma.texto.isBlank()) 0.42f else 0.22f)).dp)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    toma.nombre?.let {
                        Text(it, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                    }
                    toma.escena?.let {
                        Text(it, color = Color.White, fontSize = 15.sp, lineHeight = 20.sp)
                        Spacer(Modifier.height(6.dp))
                    }
                    toma.accion?.let { Detail("Cómo", it) }
                    toma.celular?.let { Detail("Celular", it) }
                    toma.encuadre?.let { Detail("Encuadre", it) }
                    toma.enPantalla?.let { Detail("Texto en pantalla", it) }
                    toma.cobertura?.let { Detail("Graba", it) }
                }
            }

            guion.tomas.getOrNull(index + 1)?.let { next ->
                Spacer(Modifier.height(4.dp))
                Text(
                    "Siguiente: ${next.resumen}",
                    color = Dim, fontSize = 12.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(Panel)
                        .padding(horizontal = 10.dp, vertical = 3.dp),
                )
            }
        }

        // ---------- Cuenta regresiva ----------
        countdown?.let {
            Text(
                "$it",
                color = Color.White, fontSize = 120.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        // ---------- Controles ----------
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (recording != null) {
                val limit = if (autoStop && toma.duracion > 0) " / ${toma.duracion}s" else ""
                Text(
                    "● ${elapsed}s$limit",
                    color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Color(0xCCDC2626))
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                )
                Spacer(Modifier.height(12.dp))
            }

            // Fila secundaria
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("✕") { if (!isBusy) onClose() }
                Pill("A−") { fontSize = (fontSize - 2f).coerceAtLeast(14f); prefs.fontSize = fontSize }
                Pill("A+") { fontSize = (fontSize + 2f).coerceAtMost(56f); prefs.fontSize = fontSize }
                if (!isPhoto) {
                    Pill(if (autoStop) "⏱ auto" else "⏱ libre") {
                        autoStop = !autoStop; prefs.autoStop = autoStop
                    }
                }
                Pill("⟲ cámara") {
                    if (!isBusy) { front = !front; prefs.frontCamera = front }
                }
            }
            Spacer(Modifier.height(16.dp))

            // Fila principal
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(36.dp),
            ) {
                RoundButton("◀", enabled = !isBusy && index > 0) { goTo(index - 1) }
                if (isPhoto) {
                    ShutterButton(busy = isBusy, onClick = { toggleRecord() })
                } else {
                    RecordButton(active = isBusy, onClick = { toggleRecord() })
                }
                RoundButton("▶", enabled = !isBusy && index < guion.tomas.lastIndex) { goTo(index + 1) }
            }
        }
    }
}

@Composable
private fun Chip(label: String, color: Color) {
    Text(
        label,
        color = color, fontSize = 12.sp, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.10f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
private fun UploadChip(guion: Guion, toma: Toma, done: Boolean) {
    val context = LocalContext.current
    val key = Uploader.key(guion.id, toma.orden)
    when (Uploader.states[key]) {
        UploadState.ESPERANDO -> Chip("↑ en fila", Amber)
        UploadState.SUBIENDO -> Chip("↑ ${Uploader.progress[key] ?: 0}%", Amber)
        UploadState.SUBIDO -> Chip("☁ subida", Color(0xFF60A5FA))
        UploadState.ERROR -> Text(
            "⚠ reintentar",
            color = Color(0xFFF87171), fontSize = 12.sp,
            modifier = Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.10f))
                .clickable { Uploader.retry(context, Prefs(context), guion, toma) }
                .padding(horizontal = 8.dp, vertical = 2.dp),
        )
        null -> if (done) Chip("✓", Ok)
    }
}

@Composable
private fun Detail(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, color = Dim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(92.dp))
        Text(value, color = Soft, fontSize = 13.sp, lineHeight = 17.sp, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun Pill(label: String, onClick: () -> Unit) {
    Text(
        label,
        color = Color.White, fontSize = 13.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

@Composable
private fun RoundButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = if (enabled) 0.55f else 0.25f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (enabled) Color.White else Color(0x66FFFFFF), fontSize = 22.sp)
    }
}

@Composable
private fun RecordButton(active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(80.dp)
            .clip(CircleShape)
            .border(4.dp, Color.White, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (active) {
            Box(Modifier.size(30.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFFDC2626)))
        } else {
            Box(Modifier.size(62.dp).clip(CircleShape).background(Color(0xFFDC2626)))
        }
    }
}

/** Obturador para las tomas de foto: cada toque toma una foto (con la cuenta regresiva de Ajustes). */
@Composable
private fun ShutterButton(busy: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(80.dp)
            .clip(CircleShape)
            .border(4.dp, Color.White, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(62.dp).clip(CircleShape).background(if (busy) Color(0xFF9CA3AF) else Color.White),
            contentAlignment = Alignment.Center,
        ) {
            Text("📷", fontSize = 24.sp)
        }
    }
}
