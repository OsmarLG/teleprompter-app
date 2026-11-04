package com.osmarlg.teleprompter

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.mutableStateMapOf
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
 * Sube cada toma grabada. Vive fuera de las pantallas para no cortarse al navegar.
 *
 * Con sesión del CRM sube por partes (5 MB) y se puede reanudar: si se va la señal o se cierra la app, la siguiente
 * vez continúa desde los bytes que el CRM ya tiene. Sube una toma a la vez. Cuando el CRM confirma que la recibió
 * completa, borra la copia del celular (salvo que en Ajustes se pida conservarla). Con una API propia usa el envío
 * de siempre (multipart).
 */
object Uploader {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = Mutex()

    /** clave = "guionId#orden" */
    val states = mutableStateMapOf<String, UploadState>()
    val errors = mutableStateMapOf<String, String>()

    /** Porcentaje subido por clave (0–100). */
    val progress = mutableStateMapOf<String, Int>()

    private val lastLegacy = mutableMapOf<String, Pair<Uri, String>>()

    fun key(guionId: String, orden: Int) = "$guionId#$orden"

    fun upload(context: Context, prefs: Prefs, guion: Guion, toma: Toma, uri: Uri) {
        val ctx = context.applicationContext
        if (prefs.signedIn && guion.piezaId != null && toma.id != null) {
            val item = Pending(key(guion.id, toma.orden), uri.toString(), guion.piezaId, toma.id)
            savePending(prefs, pending(prefs).filterNot { it.key == item.key } + item)
            startCrm(ctx, prefs, item)
            return
        }
        val url = Api.uploadUrl(prefs.apiUrl, guion.id, toma.orden) ?: return
        val k = key(guion.id, toma.orden)
        lastLegacy[k] = uri to url
        startLegacy(ctx, k, uri, url, prefs.apiToken, guion, toma)
    }

    fun retry(context: Context, prefs: Prefs, guion: Guion, toma: Toma) {
        val k = key(guion.id, toma.orden)
        val ctx = context.applicationContext
        pending(prefs).firstOrNull { it.key == k }?.let { startCrm(ctx, prefs, it); return }
        val (uri, url) = lastLegacy[k] ?: return
        startLegacy(ctx, k, uri, url, prefs.apiToken, guion, toma)
    }

    /** Al abrir la app: continúa las subidas al CRM que quedaron a medias. */
    fun resumePending(context: Context, prefs: Prefs) {
        if (!prefs.signedIn) return
        pending(prefs).forEach { if (states[it.key] == null) startCrm(context.applicationContext, prefs, it) }
    }

    // ---------------------------------------------------------------- CRM (por partes, reanudable)

    private data class Pending(val key: String, val uri: String, val piezaId: Long, val tomaId: Long)

    private fun pending(prefs: Prefs): List<Pending> = runCatching {
        val arr = JSONArray(prefs.pendingUploads)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Pending(o.getString("key"), o.getString("uri"), o.getLong("pieza"), o.getLong("toma"))
        }
    }.getOrDefault(emptyList())

    @Synchronized
    private fun savePending(prefs: Prefs, items: List<Pending>) {
        val arr = JSONArray()
        items.forEach { arr.put(JSONObject().put("key", it.key).put("uri", it.uri).put("pieza", it.piezaId).put("toma", it.tomaId)) }
        prefs.pendingUploads = arr.toString()
    }

    private fun startCrm(ctx: Context, prefs: Prefs, item: Pending) {
        states[item.key] = UploadState.ESPERANDO
        errors.remove(item.key)
        scope.launch {
            queue.withLock {
                states[item.key] = UploadState.SUBIENDO
                try {
                    sendCrm(ctx, prefs, item)
                    states[item.key] = UploadState.SUBIDO
                    savePending(prefs, pending(prefs).filterNot { it.key == item.key })
                } catch (e: Exception) {
                    states[item.key] = UploadState.ERROR
                    errors[item.key] = e.message ?: e.javaClass.simpleName
                }
            }
        }
    }

    private suspend fun sendCrm(ctx: Context, prefs: Prefs, item: Pending) {
        val uri = Uri.parse(item.uri)
        val base = Crm.base(prefs.crmUrl)
        val token = prefs.crmToken
        val size = fileSize(ctx, uri)
        if (size <= 0L) throw IllegalStateException("No se encontró el video en el celular.")

        val start = Crm.json(
            "POST", "$base/api/v1/app/subidas", token,
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
        progress[item.key] = percent(offset, size)

        while (!complete) {
            val chunk = readChunk(ctx, uri, offset, chunkBytes)
            val (code, text) = try {
                Crm.send("POST", "$base/api/v1/app/subidas/$uuid/partes?offset=$offset", token, chunk, "application/octet-stream", 120_000)
            } catch (e: IOException) {
                if (++failures > 6) throw IOException("Sin conexión. Toca «reintentar» cuando tengas señal.")
                delay(2_000L * failures)
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
                code >= 500 && code != 507 && ++failures <= 6 -> delay(2_000L * failures)
                else -> throw Crm.CrmException(code, Crm.errorMessage(code, text))
            }
            progress[item.key] = percent(offset, size)
        }

        if (!prefs.keepLocal) {
            runCatching { ctx.contentResolver.delete(uri, null, null) }
        }
    }

    private fun percent(offset: Long, size: Long) = if (size <= 0) 0 else ((offset * 100) / size).toInt().coerceIn(0, 100)

    private fun readChunk(ctx: Context, uri: Uri, offset: Long, length: Int): ByteArray {
        val input = ctx.contentResolver.openInputStream(uri) ?: throw IllegalStateException("No se pudo leer el video.")
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

    private fun fileSize(ctx: Context, uri: Uri): Long =
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null }
            ?: ctx.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize }
            ?: -1L

    private fun fileName(ctx: Context, uri: Uri): String =
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            ?.let { if (it.endsWith(".mp4")) it else "$it.mp4" }
            ?: "toma.mp4"

    // ---------------------------------------------------------------- API propia (multipart)

    private fun startLegacy(
        ctx: Context, k: String, uri: Uri, url: String, token: String, guion: Guion, toma: Toma,
    ) {
        states[k] = UploadState.SUBIENDO
        errors.remove(k)
        scope.launch {
            try {
                sendLegacy(ctx, uri, url, token, guion, toma)
                states[k] = UploadState.SUBIDO
            } catch (e: Exception) {
                states[k] = UploadState.ERROR
                errors[k] = e.message ?: e.javaClass.simpleName
            }
        }
    }

    private fun sendLegacy(ctx: Context, uri: Uri, url: String, token: String, guion: Guion, toma: Toma) {
        val boundary = "----tp" + UUID.randomUUID().toString().replace("-", "")
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 15_000
            conn.readTimeout = 120_000
            conn.setChunkedStreamingMode(64 * 1024)
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            conn.setRequestProperty("Accept", "application/json")
            if (token.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $token")

            DataOutputStream(conn.outputStream).use { out ->
                fun field(name: String, value: String) {
                    out.writeBytes("--$boundary\r\n")
                    out.writeBytes("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
                    out.write(value.toByteArray(Charsets.UTF_8))
                    out.writeBytes("\r\n")
                }
                field("guion_id", guion.id)
                field("orden", toma.orden.toString())
                field("texto", toma.texto)

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
