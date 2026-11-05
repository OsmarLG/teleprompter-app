package com.osmarlg.teleprompter

import android.content.Intent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaPlayer
import android.net.Uri
import android.util.LruCache
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val P get() = AppTheme.palette

fun openLink(context: android.content.Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        .onFailure { Toast.makeText(context, "No se pudo abrir la liga.", Toast.LENGTH_SHORT).show() }
}

@Composable
private fun TopBar(title: String, subtitle: String? = null, onBack: () -> Unit, actions: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoundIcon("←", onBack)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = P.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle?.let { Text(it, fontSize = 13.sp, color = P.muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        actions()
    }
}

// ============================================================== Subidas

/** El estado de cada archivo que se sube: en fila, subiendo (con %), subido o con error (reintentar). */
@Composable
fun UploadsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val items = Uploader.items
    val active = Uploader.activeCount
    val errors = Uploader.errorCount
    val done = items.count { it.state == UploadState.SUBIDO }

    Surface(Modifier.fillMaxSize(), color = P.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            TopBar(
                "Subidas",
                listOfNotNull(
                    "$active en fila".takeIf { active > 0 },
                    "$errors con error".takeIf { errors > 0 },
                    "$done subidas".takeIf { done > 0 },
                ).joinToString(" · ").ifBlank { null },
                onBack,
            )
            Text(
                "Siguen subiendo aunque salgas de la app (verás la notificación «Subiendo…»). Lo que no alcance a subir " +
                    "continúa la próxima vez que abras la app.",
                fontSize = 12.sp, color = P.muted, modifier = Modifier.padding(horizontal = 16.dp),
            )
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (errors > 0) Button(onClick = { Uploader.retryAll(context) }) { Text("Reintentar todo") }
                if (done > 0) OutlinedButton(onClick = { Uploader.clearDone() }) { Text("Quitar las subidas") }
            }
            if (items.isEmpty()) {
                EmptyState("☁", "No hay subidas todavía.", "Lo que grabes aparece aquí mientras se sube al CRM.")
            }
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(AppTheme.listGap),
            ) {
                items(items, key = { it.id }) { item -> UploadRow(item) }
            }
        }
    }
}

private val timeFormat = SimpleDateFormat("d MMM HH:mm", Locale("es", "MX"))

@Composable
private fun UploadRow(item: UploadItem) {
    val context = LocalContext.current
    val (label, color) = when (item.state) {
        UploadState.ESPERANDO -> "En fila" to P.warn
        UploadState.SUBIENDO -> "${item.progress}%" to P.warn
        UploadState.SUBIDO -> "✓ Subida" to P.ok
        UploadState.ERROR -> "Error" to P.error
    }
    StyledCard(stripe = color) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LocalThumb(item, size = 56.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(item.label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = P.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val meta = listOfNotNull(
                    item.cliente,
                    timeFormat.format(Date(item.createdAt)),
                    item.size.takeIf { it > 0 }?.let { "%.1f MB".format(it / 1_048_576.0) },
                ).joinToString(" · ")
                Text(meta, fontSize = 12.sp, color = P.muted)
            }
            Badge(label, color)
        }
        if (item.state == UploadState.SUBIENDO) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { item.progress / 100f },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)),
                color = P.accent, trackColor = P.raised,
            )
        }
        if (item.state == UploadState.ERROR) {
            item.error?.let { Text(it, color = P.error, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp)) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { Uploader.retry(context, item.id) }) { Text("Reintentar") }
                TextButton(onClick = { Uploader.remove(item.id) }) { Text("Quitar de la lista", color = P.muted) }
            }
        }
    }
}

@Composable
fun EmptyState(symbol: String, title: String, detail: String? = null) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(64.dp).clip(CircleShape).background(P.raised), contentAlignment = Alignment.Center) {
            Text(symbol, fontSize = 28.sp, color = P.accent)
        }
        Spacer(Modifier.height(12.dp))
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = P.text, textAlign = TextAlign.Center)
        detail?.let { Text(it, fontSize = 13.sp, color = P.muted, textAlign = TextAlign.Center) }
    }
}

// ============================================================== Pieza terminada

/**
 * Una pieza con lo que se subió de cada toma: vista previa de fotos y videos (desde el CRM), y ligas al CRM, al avance
 * editado y a la publicación. Desde aquí se puede volver a grabar una toma.
 */
@Composable
fun PieceScreen(initial: Guion, prefs: Prefs, onBack: () -> Unit, onRecord: (Guion, Int) -> Unit, onView: (Archivo) -> Unit) {
    val context = LocalContext.current
    var guion by remember(initial.id) { mutableStateOf(initial) }
    var loading by remember { mutableStateOf(false) }
    // Trae la pieza fresca del CRM (al abrir y cada vez que termina una subida), con las ligas firmadas vigentes.
    LaunchedEffect(initial.id, AppEvents.uploadsVersion) {
        val id = initial.piezaId ?: return@LaunchedEffect
        if (!prefs.signedIn) return@LaunchedEffect
        loading = true
        runCatching { GuionParser.parse(Api.fetch(Crm.guionesUrl(prefs.crmUrl) + "?pieza=$id", prefs.crmToken)).firstOrNull() }
            .getOrNull()?.let { guion = it }
        loading = false
    }
    Surface(Modifier.fillMaxSize(), color = P.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            TopBar(guion.titulo, listOfNotNull(guion.cliente, guion.fecha).joinToString(" · "), onBack) {
                if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = P.accent)
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                guion.estado?.let { Badge(it, if (guion.terminada) P.ok else P.warn) }
                Badge("${guion.hechas}/${guion.tomas.size} tomas", P.info)
                if (guion.archivos > 0) Badge("${guion.archivos} archivos", P.info)
            }
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                guion.publicacion?.let { url -> Button(onClick = { openLink(context, url) }) { Text("Ver publicación") } }
                guion.avance?.let { url -> OutlinedButton(onClick = { openLink(context, url) }) { Text("Ver avance editado") } }
                guion.crmUrl?.let { url -> OutlinedButton(onClick = { openLink(context, url) }) { Text("Abrir en el CRM") } }
            }
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(AppTheme.listGap),
            ) {
                items(guion.tomas, key = { it.orden }) { toma ->
                    val index = guion.tomas.indexOf(toma)
                    val local = Uploader.forTake(Uploader.key(guion.id, toma.orden)).filter { it.state != UploadState.SUBIDO }
                    val has = toma.archivos.isNotEmpty() || local.isNotEmpty()
                    StyledCard(stripe = if (has) P.ok else P.raised) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "TOMA ${toma.orden}",
                                fontSize = 12.sp, fontWeight = FontWeight.Bold, color = P.accent,
                            )
                            Spacer(Modifier.width(8.dp))
                            if (toma.esFoto) Badge("📷 fotos", P.info)
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { onRecord(guion, index) }) {
                                Text(if (has) "↻ Grabar otra vez" else "● Grabar", fontWeight = FontWeight.SemiBold)
                            }
                        }
                        Text(toma.resumen, fontSize = 14.sp, color = P.soft, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(10.dp))
                        if (!has) {
                            Text("Sin archivos en el CRM.", fontSize = 12.sp, color = P.muted)
                        }
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            toma.archivos.forEach { archivo -> Thumb(archivo) { onView(archivo) } }
                            local.forEach { item -> LocalThumb(item, size = 104.dp, tall = true) }
                        }
                    }
                }
            }
        }
    }
}

/** Miniatura de un archivo del CRM: la foto, o un cuadro del video con su duración. */
@Composable
fun Thumb(archivo: Archivo, width: Dp = 104.dp, onClick: () -> Unit) {
    val context = LocalContext.current
    val height = width * 4 / 3
    Box(
        Modifier.size(width, height).clip(RoundedCornerShape(12.dp)).background(P.raised).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val url = archivo.url
        when {
            url == null -> Text("borrado", fontSize = 11.sp, color = P.error)
            archivo.tipo == "foto" -> RemoteImage(url, maxSide = 360, modifier = Modifier.fillMaxSize(), crop = true)
            archivo.tipo == "video" -> {
                var frame by remember(archivo.uuid) { mutableStateOf(VideoFrames.cached(archivo.uuid)) }
                var failed by remember(archivo.uuid) { mutableStateOf(false) }
                LaunchedEffect(archivo.uuid) {
                    if (frame == null) {
                        frame = VideoFrames.load(context, archivo.uuid, url)
                        failed = frame == null
                    }
                }
                frame?.let {
                    Image(it.bitmap.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                }
                if (frame == null && !failed) Shimmer()
                // Degradado abajo para que la duración se lea sobre cualquier cuadro.
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.65f)),
                    ),
                )
                PlayBadge(Modifier.align(Alignment.Center))
                frame?.durationMs?.takeIf { it > 0 }?.let {
                    Text(
                        formatDuration(it), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp),
                    )
                }
            }
            else -> Text("📄", fontSize = 28.sp)
        }
    }
}

/** Lo que está en el celular y todavía no llega al CRM: miniatura con el avance encima. */
@Composable
private fun LocalThumb(item: UploadItem, size: Dp, tall: Boolean = false) {
    val context = LocalContext.current
    var frame by remember(item.id) { mutableStateOf<VideoFrames.Frame?>(null) }
    LaunchedEffect(item.id) { frame = VideoFrames.loadLocal(context, item.id, item.tipo == "foto") }
    Box(
        Modifier.size(size, if (tall) size * 4 / 3 else size).clip(RoundedCornerShape(10.dp)).background(P.raised),
        contentAlignment = Alignment.Center,
    ) {
        frame?.let { Image(it.bitmap.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            ?: Text(if (item.tipo == "foto") "📷" else "🎬", fontSize = 20.sp)
        if (tall) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)))
            Text(
                when (item.state) {
                    UploadState.ERROR -> "⚠ error"
                    UploadState.ESPERANDO -> "en fila"
                    else -> "↑ ${item.progress}%"
                },
                color = if (item.state == UploadState.ERROR) P.error else Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun PlayBadge(modifier: Modifier) {
    Box(
        modifier.size(38.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)),
        contentAlignment = Alignment.Center,
    ) {
        Text("▶", color = Color.White, fontSize = 16.sp)
    }
}

/** Relleno animado mientras carga una miniatura. */
@Composable
private fun Shimmer() {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val alpha by transition.animateFloat(
        initialValue = 0.25f, targetValue = 0.6f,
        animationSpec = infiniteRepeatable(
            tween(800),
            RepeatMode.Reverse,
        ),
        label = "alpha",
    )
    Box(Modifier.fillMaxSize().background(P.muted.copy(alpha = alpha * 0.4f)))
}

// ============================================================== Ver un archivo

@Composable
fun MediaViewer(archivo: Archivo, onBack: () -> Unit) {
    val context = LocalContext.current
    var loadingVideo by remember(archivo.uuid) { mutableStateOf(true) }
    var buffering by remember(archivo.uuid) { mutableStateOf(false) }
    var showPoster by remember(archivo.uuid) { mutableStateOf(true) }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val url = archivo.url
        when {
            url == null -> Text(
                "El original ya se borró del servidor (se guardan 60 días).",
                color = Color.White, modifier = Modifier.align(Alignment.Center).padding(24.dp),
            )
            archivo.tipo == "foto" -> RemoteImage(url, maxSide = 2048, modifier = Modifier.fillMaxSize(), crop = false)
            archivo.tipo == "video" -> {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        VideoView(ctx).apply {
                            val controller = MediaController(ctx)
                            controller.setAnchorView(this)
                            setMediaController(controller)
                            setVideoURI(Uri.parse(url))
                            setOnPreparedListener { player ->
                                loadingVideo = false
                                player.setOnInfoListener { _, what, _ ->
                                    when (what) {
                                        MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START -> showPoster = false
                                        MediaPlayer.MEDIA_INFO_BUFFERING_START -> buffering = true
                                        MediaPlayer.MEDIA_INFO_BUFFERING_END -> buffering = false
                                    }
                                    false
                                }
                                start()
                            }
                            setOnErrorListener { _, _, _ ->
                                loadingVideo = false
                                Toast.makeText(ctx, "No se pudo reproducir; ábrelo en el navegador.", Toast.LENGTH_LONG).show()
                                true
                            }
                        }
                    },
                    onRelease = { it.stopPlayback() },
                )
                // Mientras carga: el cuadro de la miniatura encima, oscurecido, con el indicador.
                if (showPoster) {
                    VideoFrames.cached(archivo.uuid)?.let {
                        Image(
                            it.bitmap.asImageBitmap(), null,
                            Modifier.fillMaxSize().background(Color.Black), contentScale = ContentScale.Fit,
                        )
                    }
                }
                if (loadingVideo || buffering) {
                    Column(
                        Modifier.align(Alignment.Center).clip(RoundedCornerShape(16.dp))
                            .background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 24.dp, vertical = 18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        CircularProgressIndicator(color = P.accent, strokeWidth = 3.dp)
                        Spacer(Modifier.height(10.dp))
                        Text(if (loadingVideo) "Cargando video…" else "Cargando…", color = Color.White, fontSize = 14.sp)
                        if (loadingVideo && archivo.tamano > 0) {
                            Text("%.1f MB".format(archivo.tamano / 1_048_576.0), color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                        }
                    }
                }
            }
            else -> Button(onClick = { openLink(context, url) }, modifier = Modifier.align(Alignment.Center)) { Text("Abrir archivo") }
        }
        Row(
            Modifier.fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent)))
                .safeDrawingPadding().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("← Volver", color = Color.White) }
            Text(archivo.nombre, color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            url?.let { TextButton(onClick = { openLink(context, it) }) { Text("Abrir", color = Color.White) } }
        }
    }
}

private val bitmapCache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap) = value.byteCount
}

/** Imagen del CRM (liga firmada), reducida a maxSide para no gastar memoria. */
@Composable
fun RemoteImage(url: String, maxSide: Int, modifier: Modifier, crop: Boolean) {
    val cacheKey = "$maxSide|${url.substringBefore('?')}"
    var bitmap by remember(url) { mutableStateOf(bitmapCache.get(cacheKey)) }
    var failed by remember(url) { mutableStateOf(false) }
    LaunchedEffect(url) {
        if (bitmap != null) return@LaunchedEffect
        val loaded = withContext(Dispatchers.IO) { runCatching { download(url, maxSide) }.getOrNull() }
        if (loaded != null) {
            bitmapCache.put(cacheKey, loaded)
            bitmap = loaded
        } else {
            failed = true
        }
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        val current = bitmap
        when {
            current != null -> Image(
                current.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize(),
                contentScale = if (crop) ContentScale.Crop else ContentScale.Fit,
            )
            failed -> Text("⚠", color = P.error, fontSize = 22.sp)
            crop -> Shimmer()
            else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = P.accent, strokeWidth = 3.dp)
                Spacer(Modifier.height(10.dp))
                Text("Cargando foto…", color = Color.White, fontSize = 14.sp)
            }
        }
    }
}

private fun download(url: String, maxSide: Int): Bitmap? {
    val conn = URL(url).openConnection() as HttpURLConnection
    val bytes = try {
        conn.connectTimeout = 10_000
        conn.readTimeout = 30_000
        if (conn.responseCode !in 200..299) return null
        conn.inputStream.use { it.readBytes() }
    } finally {
        conn.disconnect()
    }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        ?: return null
    // Las fotos de la cámara guardan el giro en EXIF; se aplica para que no salgan de lado.
    val degrees = runCatching {
        when (ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
    }.getOrDefault(0f)
    if (degrees == 0f) return decoded
    return Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, Matrix().apply { postRotate(degrees) }, true)
}
