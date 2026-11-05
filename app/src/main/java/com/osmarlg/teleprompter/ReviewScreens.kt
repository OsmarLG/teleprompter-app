package com.osmarlg.teleprompter

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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

private val Muted = Color(0xFF9CA3AF)
private val Blue = Color(0xFF60A5FA)
private val Green = Color(0xFF34D399)
private val Amber = Color(0xFFFBBF24)
private val Red = Color(0xFFF87171)

fun openLink(context: android.content.Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        .onFailure { Toast.makeText(context, "No se pudo abrir la liga.", Toast.LENGTH_SHORT).show() }
}

@Composable
private fun TopBar(title: String, subtitle: String? = null, onBack: () -> Unit, actions: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onBack) { Text("←", fontSize = 22.sp) }
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle?.let { Text(it, fontSize = 13.sp, color = Blue, maxLines = 1, overflow = TextOverflow.Ellipsis) }
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

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
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
                fontSize = 12.sp, color = Muted, modifier = Modifier.padding(horizontal = 16.dp),
            )
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (errors > 0) Button(onClick = { Uploader.retryAll(context) }) { Text("Reintentar todo") }
                if (done > 0) OutlinedButton(onClick = { Uploader.clearDone() }) { Text("Quitar las subidas") }
            }
            if (items.isEmpty()) {
                Text("No hay subidas todavía.", color = Muted, modifier = Modifier.padding(16.dp))
            }
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
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
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (item.tipo == "foto") "📷" else "🎬", fontSize = 18.sp)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val meta = listOfNotNull(
                        item.cliente,
                        timeFormat.format(Date(item.createdAt)),
                        item.size.takeIf { it > 0 }?.let { "%.1f MB".format(it / 1_048_576.0) },
                    ).joinToString(" · ")
                    Text(meta, fontSize = 12.sp, color = Muted)
                }
                val (label, color) = when (item.state) {
                    UploadState.ESPERANDO -> "En fila" to Amber
                    UploadState.SUBIENDO -> "${item.progress}%" to Amber
                    UploadState.SUBIDO -> "✓ Subida" to Green
                    UploadState.ERROR -> "Error" to Red
                }
                Text(label, color = color, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
            if (item.state == UploadState.SUBIENDO) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(progress = { item.progress / 100f }, modifier = Modifier.fillMaxWidth())
            }
            if (item.state == UploadState.ERROR) {
                item.error?.let { Text(it, color = Red, fontSize = 12.sp) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { Uploader.retry(context, item.id) }) { Text("Reintentar") }
                    TextButton(onClick = { Uploader.remove(item.id) }) { Text("Quitar de la lista", color = Muted) }
                }
            }
        }
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
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            TopBar(guion.titulo, listOfNotNull(guion.cliente, guion.estado, guion.fecha).joinToString(" · "), onBack) {
                if (loading) CircularProgressIndicator(Modifier.size(20.dp).padding(end = 4.dp), strokeWidth = 2.dp)
            }
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
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(guion.tomas, key = { it.orden }) { toma ->
                    val index = guion.tomas.indexOf(toma)
                    val local = Uploader.forTake(Uploader.key(guion.id, toma.orden)).filter { it.state != UploadState.SUBIDO }
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Column(Modifier.fillMaxWidth().padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "Toma ${toma.orden}${if (toma.esFoto) " · fotos" else ""}",
                                    fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFFF43F5E),
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = { onRecord(guion, index) }) { Text(if (toma.archivos.isEmpty()) "Grabar" else "Grabar otra vez") }
                            }
                            Text(toma.resumen, fontSize = 14.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(8.dp))
                            if (toma.archivos.isEmpty() && local.isEmpty()) {
                                Text("Sin archivos en el CRM.", fontSize = 12.sp, color = Muted)
                            }
                            Row(
                                Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                toma.archivos.forEach { archivo -> Thumb(archivo) { onView(archivo) } }
                                local.forEach { item ->
                                    Box(
                                        Modifier.size(96.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF1F2937)),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            if (item.state == UploadState.ERROR) "⚠ error" else "↑ ${item.progress}%",
                                            color = if (item.state == UploadState.ERROR) Red else Amber, fontSize = 13.sp,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Thumb(archivo: Archivo, onClick: () -> Unit) {
    Box(
        Modifier.size(96.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF1F2937)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (archivo.tipo == "foto" && archivo.url != null) {
            RemoteImage(archivo.url, maxSide = 300, modifier = Modifier.fillMaxSize(), crop = true)
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (archivo.tipo == "video") "▶" else "📄", fontSize = 28.sp, color = Color.White)
                archivo.subido?.let { Text(it.substringAfter(' '), fontSize = 11.sp, color = Muted) }
            }
        }
        if (archivo.url == null) {
            Text("borrado", fontSize = 11.sp, color = Red, modifier = Modifier.align(Alignment.BottomCenter))
        }
    }
}

// ============================================================== Ver un archivo

@Composable
fun MediaViewer(archivo: Archivo, onBack: () -> Unit) {
    val context = LocalContext.current
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val url = archivo.url
        when {
            url == null -> Text(
                "El original ya se borró del servidor (se guardan 60 días).",
                color = Color.White, modifier = Modifier.align(Alignment.Center).padding(24.dp),
            )
            archivo.tipo == "foto" -> RemoteImage(url, maxSide = 2048, modifier = Modifier.fillMaxSize(), crop = false)
            archivo.tipo == "video" -> AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    VideoView(ctx).apply {
                        val controller = MediaController(ctx)
                        controller.setAnchorView(this)
                        setMediaController(controller)
                        setVideoURI(Uri.parse(url))
                        setOnPreparedListener { start() }
                        setOnErrorListener { _, _, _ ->
                            Toast.makeText(ctx, "No se pudo reproducir; ábrelo en el navegador.", Toast.LENGTH_LONG).show()
                            true
                        }
                    }
                },
                onRelease = { it.stopPlayback() },
            )
            else -> Button(onClick = { openLink(context, url) }, modifier = Modifier.align(Alignment.Center)) { Text("Abrir archivo") }
        }
        Row(
            Modifier.fillMaxWidth().safeDrawingPadding().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("← Volver", color = Color.White) }
            Text(archivo.nombre, color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            url?.let { TextButton(onClick = { openLink(context, it) }) { Text("Abrir", color = Blue) } }
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
            failed -> Text("⚠", color = Red, fontSize = 22.sp)
            else -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
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
