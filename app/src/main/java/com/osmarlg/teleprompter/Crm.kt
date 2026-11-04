package com.osmarlg.teleprompter

import android.net.Uri
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Conexión con el CRM de ELROI (OS CRM). La app inicia sesión con el correo y la contraseña del CRM, o escaneando el
 * QR de «Vincular la app» en el perfil; el CRM le da un token solo para Grabaciones.
 *
 *   POST {crm}/api/v1/app/login      {email, password, device}  -> {token, user, device}
 *   POST {crm}/api/v1/app/vincular   {code, device}             -> {token, user, device}
 *   GET  {crm}/api/v1/app/me                                     -> {user, device}
 *   POST {crm}/api/v1/app/logout
 *   GET  {crm}/api/v1/app/guiones                                -> {guiones: [...], clientes: [...]}
 *   POST {crm}/api/v1/app/subidas    {shoot_piece_id, shoot_take_id, file_name, size} -> {uuid, offset, chunk_bytes}
 *   POST {crm}/api/v1/app/subidas/{uuid}/partes?offset=N  (cuerpo = bytes) -> {offset, complete, take_done}
 */
object Crm {
    const val DEFAULT_URL = "https://oscrm.apps.elroi.cloud"
    const val PAIR_SCHEME = "oscrm-teleprompter"

    /** Versión de la app, para que el CRM la muestre en el perfil. */
    var appVersion: String = ""

    class CrmException(val status: Int, message: String) : Exception(message)

    data class Session(val token: String, val userName: String, val email: String, val client: String?)

    data class Pairing(val url: String, val code: String)

    val deviceName: String
        get() = listOf(Build.MANUFACTURER.replaceFirstChar { it.uppercase() }, Build.MODEL)
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" ")
            .ifBlank { "Android" }

    fun base(url: String): String = url.trim().trimEnd('/').ifBlank { DEFAULT_URL }

    fun guionesUrl(url: String) = "${base(url)}/api/v1/app/guiones"

    /** Lee el QR del perfil: oscrm-teleprompter://vincular?url=…&code=… */
    fun parsePairing(raw: String?): Pairing? {
        if (raw.isNullOrBlank()) return null
        val uri = runCatching { Uri.parse(raw.trim()) }.getOrNull() ?: return null
        if (uri.scheme != PAIR_SCHEME) return null
        val code = uri.getQueryParameter("code")?.takeIf { it.isNotBlank() } ?: return null
        return Pairing(uri.getQueryParameter("url")?.takeIf { it.isNotBlank() } ?: DEFAULT_URL, code)
    }

    suspend fun login(url: String, email: String, password: String): Session = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("email", email.trim())
            .put("password", password)
            .put("device", deviceName)
        session(json("POST", "${base(url)}/api/v1/app/login", null, body))
    }

    suspend fun pair(pairing: Pairing): Session = withContext(Dispatchers.IO) {
        val body = JSONObject().put("code", pairing.code).put("device", deviceName)
        session(json("POST", "${base(pairing.url)}/api/v1/app/vincular", null, body))
    }

    suspend fun logout(url: String, token: String) = withContext(Dispatchers.IO) {
        runCatching { json("POST", "${base(url)}/api/v1/app/logout", token, JSONObject()) }
    }

    private fun session(response: JSONObject): Session {
        val user = response.optJSONObject("user") ?: JSONObject()
        return Session(
            token = response.getString("token"),
            userName = user.optString("name", ""),
            email = user.optString("email", ""),
            client = user.optString("client", "").takeIf { it.isNotBlank() && it != "null" },
        )
    }

    /** Una llamada JSON; lanza CrmException con el mensaje del CRM si no es 2xx. */
    fun json(method: String, url: String, token: String?, body: JSONObject?): JSONObject {
        val (code, text) = send(method, url, token, body?.toString()?.toByteArray(Charsets.UTF_8), "application/json")
        if (code !in 200..299) throw CrmException(code, errorMessage(code, text))
        return if (text.isBlank()) JSONObject() else JSONObject(text)
    }

    /** Llamada cruda: regresa el código HTTP y el cuerpo, sin lanzar por códigos de error. */
    fun send(
        method: String,
        url: String,
        token: String?,
        body: ByteArray?,
        contentType: String,
        timeoutMs: Int = 30_000,
    ): Pair<Int, String> {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 15_000
            conn.readTimeout = timeoutMs
            conn.setRequestProperty("Accept", "application/json")
            if (appVersion.isNotBlank()) conn.setRequestProperty("X-App-Version", appVersion)
            if (!token.isNullOrBlank()) conn.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", contentType)
                conn.setFixedLengthStreamingMode(body.size)
                conn.outputStream.use { it.write(body) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            return code to text
        } finally {
            conn.disconnect()
        }
    }

    fun errorMessage(code: Int, text: String): String {
        val json = runCatching { JSONObject(text) }.getOrNull()
        val errors = json?.optJSONObject("errors")
        val first = errors?.keys()?.asSequence()?.firstOrNull()?.let { errors.optJSONArray(it)?.optString(0) }
        val message = first ?: json?.optString("message")?.takeIf { it.isNotBlank() }
        return when {
            message != null -> message
            code == 401 -> "La sesión se cerró. Vuelve a iniciar sesión."
            code == 413 -> "El archivo es demasiado grande para el servidor."
            else -> "Error del CRM ($code)."
        }
    }
}
