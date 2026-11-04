package com.osmarlg.teleprompter

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

enum class UploadState { SUBIENDO, SUBIDO, ERROR }

/** Sube cada toma grabada a la API. Vive fuera de las pantallas para no cortarse al navegar. */
object Uploader {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** clave = "guionId#orden" */
    val states = mutableStateMapOf<String, UploadState>()
    val errors = mutableStateMapOf<String, String>()
    private val lastUri = mutableMapOf<String, Pair<Uri, String>>()

    fun key(guionId: String, orden: Int) = "$guionId#$orden"

    fun upload(context: Context, prefs: Prefs, guion: Guion, toma: Toma, uri: Uri) {
        val url = Api.uploadUrl(prefs.apiUrl, guion.id, toma.orden) ?: return
        val k = key(guion.id, toma.orden)
        lastUri[k] = uri to url
        start(context.applicationContext, k, uri, url, prefs.apiToken, guion, toma)
    }

    fun retry(context: Context, prefs: Prefs, guion: Guion, toma: Toma) {
        val k = key(guion.id, toma.orden)
        val (uri, url) = lastUri[k] ?: return
        start(context.applicationContext, k, uri, url, prefs.apiToken, guion, toma)
    }

    private fun start(
        ctx: Context, k: String, uri: Uri, url: String, token: String, guion: Guion, toma: Toma,
    ) {
        states[k] = UploadState.SUBIENDO
        errors.remove(k)
        scope.launch {
            try {
                send(ctx, uri, url, token, guion, toma)
                states[k] = UploadState.SUBIDO
            } catch (e: Exception) {
                states[k] = UploadState.ERROR
                errors[k] = e.message ?: e.javaClass.simpleName
            }
        }
    }

    private fun fileName(ctx: Context, uri: Uri): String =
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            ?.let { if (it.endsWith(".mp4")) it else "$it.mp4" }
            ?: "toma.mp4"

    private fun send(ctx: Context, uri: Uri, url: String, token: String, guion: Guion, toma: Toma) {
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
