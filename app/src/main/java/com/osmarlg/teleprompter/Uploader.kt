package com.osmarlg.teleprompter

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.mutableStateListOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

enum class UploadState { ESPERANDO, SUBIENDO, SUBIDO, ERROR }

/**
 * Un archivo grabado que se sube (una foto o un video; una toma de fotos tiene varios). Guarda la cuenta con la que se
 * grabó, aunque luego se cambie de cuenta.
 */
data class UploadItem(
    /** La uri del archivo en el celular. */
    val id: String,
    /** "guionId#orden": la toma a la que pertenece. */
    val takeKey: String,
    val titulo: String,
    val orden: Int,
    val cliente: String?,
    /** "foto" o "video". */
    val tipo: String,
    val createdAt: Long,
    val piezaId: Long?,
    val tomaId: Long?,
    val url: String,
    val token: String,
    /** Con una API propia (multipart) en vez del CRM. */
    val legacy: Boolean = false,
    val texto: String = "",
    val state: UploadState = UploadState.ESPERANDO,
    val progress: Int = 0,
    val error: String? = null,
    val size: Long = 0L,
) {
    val guionId: String get() = takeKey.substringBeforeLast('#')
    val label: String get() = "$titulo · Toma $orden"
}

/**
 * Sube cada foto o video grabado. Vive fuera de las pantallas y, mientras sube, mantiene un servicio en primer plano
 * (UploadService, con su notificación) para que la subida siga aunque se salga de la app o se apague la pantalla.
 *
 * Con sesión del CRM sube por partes (5 MB) y se puede reanudar: si se va la señal o se cierra la app, la siguiente
 * vez continúa desde los bytes que el CRM ya tiene. Sube un archivo a la vez. Cuando el CRM confirma que lo recibió
 * completo, borra la copia del celular (salvo que en Ajustes se pida conservarla). Con una API propia usa el envío de
 * siempre (multipart). La lista queda guardada para la pantalla «Subidas» (las terminadas se quedan las últimas 60).
 */
object Uploader {
    private const val KEEP_DONE = 60

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = Mutex()
    private val lock = Any()
    private var loaded = false
    private var prefs: Prefs? = null

    /** Todas las subidas, la más nueva primero. */
    val items = mutableStateListOf<UploadItem>()

    fun key(guionId: String, orden: Int) = "$guionId#$orden"

    val activeCount: Int get() = items.count { it.state == UploadState.ESPERANDO || it.state == UploadState.SUBIENDO }
    val errorCount: Int get() = items.count { it.state == UploadState.ERROR }

    fun forTake(takeKey: String): List<UploadItem> = items.filter { it.takeKey == takeKey }

    fun find(id: String): UploadItem? = items.firstOrNull { it.id == id }

    /** Carga la lista guardada (una vez por proceso). Lo que estaba subiendo cuando se cerró la app vuelve a la fila. */
    fun init(context: Context) {
        synchronized(lock) {
            if (loaded) return
            loaded = true
            val p = Prefs(context.applicationContext)
            prefs = p
            val saved = parse(p.uploads).map { if (it.state == UploadState.SUBIENDO) it.copy(state = UploadState.ESPERANDO) else it }
            val migrated = parseOld(p).filterNot { old -> saved.any { it.id == old.id } }
            if (migrated.isNotEmpty()) p.pendingUploads = "[]"
            items.clear()
            items.addAll((migrated + saved).sortedByDescending { it.createdAt })
        }
        save()
    }

    fun upload(context: Context, prefs: Prefs, guion: Guion, toma: Toma, uri: Uri) {
        init(context)
        val ctx = context.applicationContext
        val tipo = if (ctx.contentResolver.getType(uri)?.startsWith("image/") == true || toma.esFoto) "foto" else "video"
        val base = UploadItem(
            id = uri.toString(), takeKey = key(guion.id, toma.orden), titulo = guion.titulo, orden = toma.orden,
            cliente = guion.cliente, tipo = tipo, createdAt = System.currentTimeMillis(),
            piezaId = guion.piezaId, tomaId = toma.id, url = "", token = "",
        )
        val item = when {
            prefs.signedIn && guion.piezaId != null && toma.id != null ->
                base.copy(url = Crm.base(prefs.crmUrl), token = prefs.crmToken)
            else -> {
                val url = Api.uploadUrl(prefs.apiUrl, guion.id, toma.orden) ?: return
                base.copy(url = url, token = prefs.apiToken, legacy = true, texto = toma.texto)
            }
        }
        synchronized(lock) {
            items.removeAll { it.id == item.id }
            items.add(0, item)
        }
        save()
        start(ctx, item.id)
    }

    fun retry(context: Context, id: String) {
        init(context)
        val item = find(id) ?: return
        if (item.state == UploadState.SUBIENDO || item.state == UploadState.ESPERANDO) return
        start(context.applicationContext, id)
    }

    /** Reintenta lo que falló de una toma (el chip «reintentar» de la pantalla de grabar). */
    fun retryTake(context: Context, takeKey: String) {
        forTake(takeKey).filter { it.state == UploadState.ERROR }.forEach { retry(context, it.id) }
    }

    fun retryAll(context: Context) {
        items.filter { it.state == UploadState.ERROR }.forEach { retry(context, it.id) }
    }

    /** Quita de la lista una subida que no está en curso (la copia del celular no se toca). */
    fun remove(id: String) {
        synchronized(lock) {
            items.removeAll { it.id == id && it.state != UploadState.SUBIENDO }
        }
        save()
    }

    fun clearDone() {
        synchronized(lock) { items.removeAll { it.state == UploadState.SUBIDO } }
        save()
    }

    /** Al abrir la app: continúa las subidas que quedaron en fila (cada una con su cuenta). */
    fun resumePending(context: Context) {
        init(context)
        items.filter { it.state == UploadState.ESPERANDO }.forEach { start(context.applicationContext, it.id) }
    }

    // ---------------------------------------------------------------- fila

    private fun update(id: String, persist: Boolean = true, change: (UploadItem) -> UploadItem) {
        synchronized(lock) {
            val i = items.indexOfFirst { it.id == id }
            if (i >= 0) items[i] = change(items[i])
        }
        if (persist) save()
    }

    private fun start(ctx: Context, id: String) {
        update(id) { it.copy(state = UploadState.ESPERANDO, error = null) }
        UploadService.start(ctx)
        scope.launch {
            queue.withLock {
                val item = find(id)?.takeIf { it.state == UploadState.ESPERANDO } ?: return@withLock
                update(id) { it.copy(state = UploadState.SUBIENDO) }
                try {
                    if (item.legacy) sendLegacy(ctx, item) else sendCrm(ctx, item)
                    update(id) { it.copy(state = UploadState.SUBIDO, progress = 100, error = null) }
                    AppEvents.uploadsVersion++
                } catch (e: Exception) {
                    update(id) { it.copy(state = UploadState.ERROR, error = e.message ?: e.javaClass.simpleName) }
                }
            }
        }
    }

    private fun save() {
        val p = prefs ?: return
        val snapshot = synchronized(lock) {
            val done = items.filter { it.state == UploadState.SUBIDO }.drop(KEEP_DONE).map { it.id }.toSet()
            if (done.isNotEmpty()) items.removeAll { it.id in done }
            items.toList()
        }
        val arr = JSONArray()
        snapshot.forEach {
            arr.put(
                JSONObject().put("id", it.id).put("take", it.takeKey).put("titulo", it.titulo).put("orden", it.orden)
                    .put("cliente", it.cliente ?: "").put("tipo", it.tipo).put("created", it.createdAt)
                    .put("pieza", it.piezaId ?: 0L).put("toma", it.tomaId ?: 0L).put("url", it.url).put("token", it.token)
                    .put("legacy", it.legacy).put("texto", it.texto).put("state", it.state.name)
                    .put("progress", it.progress).put("error", it.error ?: "").put("size", it.size),
            )
        }
        p.uploads = arr.toString()
    }

    private fun parse(raw: String): List<UploadItem> = runCatching {
        if (raw.isBlank()) return emptyList()
        val arr = JSONArray(raw)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            UploadItem(
                id = o.getString("id"), takeKey = o.getString("take"), titulo = o.optString("titulo", "Toma"),
                orden = o.optInt("orden", 0), cliente = o.optString("cliente").ifBlank { null },
                tipo = o.optString("tipo", "video"), createdAt = o.optLong("created", 0L),
                piezaId = o.optLong("pieza", 0L).takeIf { it > 0 }, tomaId = o.optLong("toma", 0L).takeIf { it > 0 },
                url = o.optString("url"), token = o.optString("token"), legacy = o.optBoolean("legacy", false),
                texto = o.optString("texto"),
                state = runCatching { UploadState.valueOf(o.optString("state")) }.getOrDefault(UploadState.ESPERANDO),
                progress = o.optInt("progress", 0), error = o.optString("error").ifBlank { null },
                size = o.optLong("size", 0L),
            )
        }
    }.getOrDefault(emptyList())

    /** Las subidas pendientes de la versión anterior de la app. */
    private fun parseOld(p: Prefs): List<UploadItem> = runCatching {
        val arr = JSONArray(p.pendingUploads)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val key = o.getString("key")
            UploadItem(
                id = o.getString("uri"), takeKey = key, titulo = "Toma pendiente",
                orden = key.substringAfterLast('#').toIntOrNull() ?: 0, cliente = null, tipo = "video",
                createdAt = System.currentTimeMillis(), piezaId = o.getLong("pieza"), tomaId = o.getLong("toma"),
                url = o.optString("url", Crm.base(p.crmUrl)), token = o.optString("token", p.crmToken),
            )
        }
    }.getOrDefault(emptyList())

    // ---------------------------------------------------------------- CRM (por partes, reanudable)

    private suspend fun sendCrm(ctx: Context, item: UploadItem) {
        val uri = Uri.parse(item.id)
        val size = fileSize(ctx, uri)
        if (size <= 0L) throw IllegalStateException("Ya no está el archivo en el celular.")
        update(item.id, persist = false) { it.copy(size = size) }

        val start = Crm.json(
            "POST", "${item.url}/api/v1/app/subidas", item.token,
            JSONObject()
                .put("shoot_piece_id", item.piezaId)
                .put("shoot_take_id", item.tomaId)
                .put("file_name", fileName(ctx, uri))
                .put("size", size),
        )
        val uuid = start.getString("uuid")
        val chunkBytes = start.optInt("chunk_bytes", 5 * 1024 * 1024).coerceIn(256 * 1024, 16 * 1024 * 1024)
        var offset = start.optLong("offset", 0L)
        var complete = start.optBoolean("complete", false)
        var failures = 0
        update(item.id, persist = false) { it.copy(progress = percent(offset, size)) }

        while (!complete) {
            val chunk = readChunk(ctx, uri, offset, chunkBytes)
            val (code, text) = try {
                Crm.send("POST", "${item.url}/api/v1/app/subidas/$uuid/partes?offset=$offset", item.token, chunk, "application/octet-stream", 120_000)
            } catch (e: IOException) {
                if (++failures > 8) throw IOException("Sin conexión. Toca «Reintentar» cuando tengas señal.")
                delay(3_000L * failures)
                continue
            }
            val json = runCatching { JSONObject(text) }.getOrNull() ?: JSONObject()
            when {
                code == 409 -> offset = json.optLong("offset", offset)
                code in 200..299 -> {
                    failures = 0
                    offset = json.optLong("offset", offset + chunk.size)
                    complete = json.optBoolean("complete", false)
                }
                code >= 500 && code != 507 && ++failures <= 8 -> delay(3_000L * failures)
                else -> throw Crm.CrmException(code, Crm.errorMessage(code, text))
            }
            update(item.id, persist = false) { it.copy(progress = percent(offset, size)) }
        }

        val keep = prefs?.keepLocal ?: true
        if (!keep) {
            runCatching { ctx.contentResolver.delete(uri, null, null) }
        }
    }

    private fun percent(offset: Long, size: Long) = if (size <= 0) 0 else ((offset * 100) / size).toInt().coerceIn(0, 100)

    private fun readChunk(ctx: Context, uri: Uri, offset: Long, length: Int): ByteArray {
        val input = ctx.contentResolver.openInputStream(uri) ?: throw IllegalStateException("No se pudo leer el archivo.")
        input.use { stream ->
            var skipped = 0L
            while (skipped < offset) {
                val n = stream.skip(offset - skipped)
                if (n <= 0) break
                skipped += n
            }
            val buffer = ByteArray(length)
            var read = 0
            while (read < length) {
                val n = stream.read(buffer, read, length - read)
                if (n < 0) break
                read += n
            }
            return if (read == length) buffer else buffer.copyOf(read)
        }
    }

    private fun fileSize(ctx: Context, uri: Uri): Long = runCatching {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null }
            ?: ctx.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize }
    }.getOrNull() ?: -1L

    /** El nombre del archivo con su extensión (.mp4 para video, .jpg para foto). */
    private fun fileName(ctx: Context, uri: Uri): String {
        val extension = if (ctx.contentResolver.getType(uri)?.startsWith("image/") == true) "jpg" else "mp4"
        val name = ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            ?: "toma"
        return if (name.substringAfterLast('.', "").lowercase() in setOf("mp4", "jpg", "jpeg")) name else "$name.$extension"
    }

    // ---------------------------------------------------------------- API propia (multipart)

    private fun sendLegacy(ctx: Context, item: UploadItem) {
        val uri = Uri.parse(item.id)
        val boundary = "----tp" + UUID.randomUUID().toString().replace("-", "")
        val conn = URL(item.url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 15_000
            conn.readTimeout = 120_000
            conn.setChunkedStreamingMode(64 * 1024)
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            conn.setRequestProperty("Accept", "application/json")
            if (item.token.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer ${item.token}")

            DataOutputStream(conn.outputStream).use { out ->
                fun field(name: String, value: String) {
                    out.writeBytes("--$boundary\r\n")
                    out.writeBytes("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
                    out.write(value.toByteArray(Charsets.UTF_8))
                    out.writeBytes("\r\n")
                }
                field("guion_id", item.guionId)
                field("orden", item.orden.toString())
                field("texto", item.texto)

                out.writeBytes("--$boundary\r\n")
                out.writeBytes("Content-Disposition: form-data; name=\"video\"; filename=\"${fileName(ctx, uri)}\"\r\n")
                out.writeBytes("Content-Type: video/mp4\r\n\r\n")
                ctx.contentResolver.openInputStream(uri)?.use { it.copyTo(out, 64 * 1024) }
                    ?: throw IllegalStateException("No se pudo leer el video")
                out.writeBytes("\r\n--$boundary--\r\n")
            }

            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw RuntimeException("HTTP $code ${err.take(150)}")
            }
        } finally {
            conn.disconnect()
        }
    }
}
