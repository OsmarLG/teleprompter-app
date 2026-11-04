package com.osmarlg.teleprompter

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class Toma(
    val orden: Int,
    val texto: String,
    val duracion: Int, // segundos; 0 = sin límite
)

data class Guion(
    val id: String,
    val titulo: String,
    val tomas: List<Toma>,
) {
    val duracionTotal: Int get() = tomas.sumOf { it.duracion }
}

/**
 * Acepta varios formatos de respuesta:
 *  - [ {guion}, {guion} ]
 *  - { "guiones": [ ... ] }  (también "data" o "scripts")
 *  - { guion }               (un solo guion)
 * Campos aceptados (español o inglés): titulo/title, tomas/takes,
 * texto/text, duracion/duration, orden/order.
 */
object GuionParser {

    fun parse(raw: String): List<Guion> {
        val trimmed = raw.trim()
        if (trimmed.startsWith("[")) return parseArray(JSONArray(trimmed))
        val obj = JSONObject(trimmed)
        for (key in listOf("guiones", "data", "scripts", "items")) {
            val arr = obj.optJSONArray(key)
            if (arr != null) return parseArray(arr)
        }
        return listOf(parseGuion(obj, 0))
    }

    private fun parseArray(arr: JSONArray): List<Guion> =
        (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { parseGuion(it, i) }
        }.filter { it.tomas.isNotEmpty() }

    private fun parseGuion(obj: JSONObject, index: Int): Guion {
        val tomasArr = obj.optJSONArray("tomas") ?: obj.optJSONArray("takes") ?: JSONArray()
        val tomas = (0 until tomasArr.length()).mapNotNull { i ->
            when (val item = tomasArr.get(i)) {
                is String -> Toma(i + 1, item, 0)
                is JSONObject -> Toma(
                    orden = item.optInt("orden", item.optInt("order", i + 1)),
                    texto = item.optString("texto", item.optString("text", "")),
                    duracion = item.optInt("duracion", item.optInt("duration", 0)),
                )
                else -> null
            }
        }.filter { it.texto.isNotBlank() }.sortedBy { it.orden }

        return Guion(
            id = obj.optString("id", "guion-${index + 1}"),
            titulo = obj.optString("titulo", obj.optString("title", "Guion ${index + 1}")),
            tomas = tomas,
        )
    }
}

/**
 * Contrato con la API (URL base, ej. https://mi-api.com/api):
 *   GET  {base}/guiones                              -> lista de guiones
 *   POST {base}/guiones/{id}/tomas/{orden}/video     -> multipart, campo "video"
 * Si la URL termina en .json se usa tal cual para leer guiones (sin subida).
 */
object Api {
    fun guionesUrl(base: String): String {
        val b = base.trimEnd('/')
        return if (b.endsWith(".json") || b.endsWith("/guiones")) b else "$b/guiones"
    }

    fun uploadUrl(base: String, guionId: String, orden: Int): String? {
        val b = base.trimEnd('/').removeSuffix("/guiones")
        if (b.isBlank() || b.endsWith(".json")) return null
        val id = java.net.URLEncoder.encode(guionId, "UTF-8")
        return "$b/guiones/$id/tomas/$orden/video"
    }

    suspend fun fetch(url: String, token: String): String = withContext(Dispatchers.IO) {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("Accept", "application/json")
            if (token.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $token")
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw RuntimeException("HTTP $code ${err.take(200)}")
            }
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}

enum class VolumeMode { NAVEGAR, GRABAR }

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("teleprompter", Context.MODE_PRIVATE)

    var apiUrl: String
        get() = sp.getString("apiUrl", "") ?: ""
        set(v) = sp.edit().putString("apiUrl", v.trim()).apply()

    var apiToken: String
        get() = sp.getString("apiToken", "") ?: ""
        set(v) = sp.edit().putString("apiToken", v.trim()).apply()

    var cache: String
        get() = sp.getString("cache", "") ?: ""
        set(v) = sp.edit().putString("cache", v).apply()

    var fontSize: Float
        get() = sp.getFloat("fontSize", 26f)
        set(v) = sp.edit().putFloat("fontSize", v).apply()

    var countdown: Int
        get() = sp.getInt("countdown", 3)
        set(v) = sp.edit().putInt("countdown", v).apply()

    var autoStop: Boolean
        get() = sp.getBoolean("autoStop", true)
        set(v) = sp.edit().putBoolean("autoStop", v).apply()

    var autoAdvance: Boolean
        get() = sp.getBoolean("autoAdvance", true)
        set(v) = sp.edit().putBoolean("autoAdvance", v).apply()

    var autoUpload: Boolean
        get() = sp.getBoolean("autoUpload", true)
        set(v) = sp.edit().putBoolean("autoUpload", v).apply()

    var frontCamera: Boolean
        get() = sp.getBoolean("frontCamera", true)
        set(v) = sp.edit().putBoolean("frontCamera", v).apply()

    var volumeMode: VolumeMode
        get() = runCatching { VolumeMode.valueOf(sp.getString("volumeMode", "NAVEGAR")!!) }
            .getOrDefault(VolumeMode.NAVEGAR)
        set(v) = sp.edit().putString("volumeMode", v.name).apply()
}

val DEMO_JSON = """
{
  "guiones": [
    {
      "id": "demo",
      "titulo": "Demo: cómo usar el teleprompter",
      "tomas": [
        { "orden": 1, "texto": "¿Sabías que puedes grabar tus videos leyendo el guion aquí mismo?", "duracion": 4 },
        { "orden": 2, "texto": "Cada toma aparece arriba, junto a la cámara, para que no se note que estás leyendo.", "duracion": 6 },
        { "orden": 3, "texto": "Le das grabar, cuenta 3, 2, 1, y se detiene sola al terminar el tiempo de la toma.", "duracion": 6 },
        { "orden": 4, "texto": "Luego pasa a la siguiente. Si te equivocas, regresas y repites solo esa.", "duracion": 5 },
        { "orden": 5, "texto": "Configura la URL de tu API en Ajustes y aquí te aparecen tus guiones.", "duracion": 5 }
      ]
    }
  ]
}
""".trimIndent()
