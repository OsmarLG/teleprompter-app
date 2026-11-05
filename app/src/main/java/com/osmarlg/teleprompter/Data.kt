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
    /** Id de la toma en el CRM (para subirla ahí); null con una API propia. */
    val id: Long? = null,
    /** Qué se ve (el CRM lo manda aparte del texto a decir). */
    val escena: String? = null,
    /** Cómo se dice: «Tal cual», «Con tus palabras» o «Sin hablar». */
    val decir: String? = null,
    val hecha: Boolean = false,
    val subidas: Int = 0,
    /** "foto" o "video". */
    val tipo: String = "video",
    /** Fotos que pide la toma (solo fotos). */
    val fotos: Int = 0,
    /** Nombre de la toma (vacío si repetía la escena). */
    val nombre: String? = null,
    /** Qué hacer. */
    val accion: String? = null,
    /** Dónde va el celular. */
    val celular: String? = null,
    val encuadre: String? = null,
    /** Texto que va en pantalla en la edición. */
    val enPantalla: String? = null,
    /** Cuántas veces grabarla, etc. */
    val cobertura: String? = null,
) {
    val esFoto: Boolean get() = tipo == "foto"

    /** Lo que se muestra cuando no hay texto a decir. */
    val resumen: String get() = texto.ifBlank { nombre ?: escena ?: accion ?: "" }
}

data class Guion(
    val id: String,
    val titulo: String,
    val tomas: List<Toma>,
    /** Pieza de Grabaciones en el CRM; null con una API propia. */
    val piezaId: Long? = null,
    val cliente: String? = null,
    val modo: String? = null,
    val modoLabel: String? = null,
    val fecha: String? = null,
    val estado: String? = null,
    val mio: Boolean = false,
) {
    val duracionTotal: Int get() = tomas.sumOf { it.duracion }
    val hechas: Int get() = tomas.count { it.hecha }
}

/**
 * Acepta varios formatos de respuesta:
 *  - [ {guion}, {guion} ]
 *  - { "guiones": [ ... ] }  (también "data" o "scripts")
 *  - { guion }               (un solo guion)
 * Campos aceptados (español o inglés): titulo/title, tomas/takes,
 * texto/text, duracion/duration, orden/order. Del CRM además: id de pieza y de toma, escena, decir, hecha, cliente,
 * modo, fecha, mio.
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

    private fun JSONObject.text(key: String): String? =
        if (isNull(key)) null else optString(key, "").takeIf { it.isNotBlank() }

    private fun parseGuion(obj: JSONObject, index: Int): Guion {
        val tomasArr = obj.optJSONArray("tomas") ?: obj.optJSONArray("takes") ?: JSONArray()
        val tomas = (0 until tomasArr.length()).mapNotNull { i ->
            when (val item = tomasArr.get(i)) {
                is String -> Toma(i + 1, item, 0)
                is JSONObject -> Toma(
                    orden = item.optInt("orden", item.optInt("order", i + 1)),
                    texto = item.text("texto") ?: item.text("text") ?: "",
                    duracion = item.optInt("duracion", item.optInt("duration", 0)),
                    id = if (item.has("id") && !item.isNull("id")) item.optLong("id") else null,
                    escena = item.text("escena"),
                    decir = item.text("decir"),
                    hecha = item.optBoolean("hecha", false),
                    subidas = item.optInt("subidas", 0),
                    tipo = item.text("tipo") ?: "video",
                    fotos = item.optInt("fotos", 0),
                    nombre = item.text("nombre"),
                    accion = item.text("accion"),
                    celular = item.text("celular"),
                    encuadre = item.text("encuadre"),
                    enPantalla = item.text("en_pantalla"),
                    cobertura = item.text("cobertura"),
                )
                else -> null
            }
        }.filter { it.resumen.isNotBlank() }.sortedBy { it.orden }

        val fromCrm = obj.has("modo")
        return Guion(
            id = obj.optString("id", "guion-${index + 1}"),
            titulo = obj.optString("titulo", obj.optString("title", "Guion ${index + 1}")),
            tomas = tomas,
            piezaId = if (fromCrm && obj.has("id")) obj.optLong("id") else null,
            cliente = obj.text("cliente"),
            modo = obj.text("modo"),
            modoLabel = obj.text("modo_label"),
            fecha = obj.text("fecha"),
            estado = obj.text("estado"),
            mio = obj.optBoolean("mio", false),
        )
    }
}

/**
 * Contrato con una API propia (URL base, ej. https://mi-api.com/api), para quien no usa el CRM:
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
            if (Crm.appVersion.isNotBlank()) conn.setRequestProperty("X-App-Version", Crm.appVersion)
            if (token.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $token")
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw Crm.CrmException(code, Crm.errorMessage(code, err))
            }
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}

enum class VolumeMode { NAVEGAR, GRABAR }

/** Una cuenta del CRM guardada en el teléfono (para cambiar de cuenta sin volver a escribir la contraseña). */
data class SavedAccount(val url: String, val token: String, val name: String, val email: String)

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("teleprompter", Context.MODE_PRIVATE)

    /** URL del CRM (por defecto el de ELROI). */
    var crmUrl: String
        get() = sp.getString("crmUrl", Crm.DEFAULT_URL) ?: Crm.DEFAULT_URL
        set(v) = sp.edit().putString("crmUrl", v.trim()).apply()

    /** Token del dispositivo que dio el CRM al iniciar sesión; vacío = sin sesión. */
    var crmToken: String
        get() = sp.getString("crmToken", "") ?: ""
        set(v) = sp.edit().putString("crmToken", v.trim()).apply()

    var crmUser: String
        get() = sp.getString("crmUser", "") ?: ""
        set(v) = sp.edit().putString("crmUser", v).apply()

    var crmEmail: String
        get() = sp.getString("crmEmail", "") ?: ""
        set(v) = sp.edit().putString("crmEmail", v).apply()

    val signedIn: Boolean get() = crmToken.isNotBlank()

    /** Las cuentas con las que se ha entrado en este teléfono; la activa es la de crmToken. */
    val accounts: List<SavedAccount>
        get() = runCatching {
            val arr = JSONArray(sp.getString("accounts", "[]") ?: "[]")
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                SavedAccount(o.getString("url"), o.getString("token"), o.optString("name"), o.optString("email"))
            }
        }.getOrDefault(emptyList())

    private fun saveAccounts(items: List<SavedAccount>) {
        val arr = JSONArray()
        items.forEach { arr.put(JSONObject().put("url", it.url).put("token", it.token).put("name", it.name).put("email", it.email)) }
        sp.edit().putString("accounts", arr.toString()).apply()
    }

    /** Entra (o vuelve a entrar) con una cuenta: queda guardada y activa. */
    fun signIn(url: String, session: Crm.Session) {
        val account = SavedAccount(url, session.token, session.userName, session.email)
        saveAccounts(listOf(account) + accounts.filterNot { it.url == url && it.email.equals(session.email, ignoreCase = true) })
        use(account)
    }

    fun use(account: SavedAccount) {
        crmUrl = account.url
        crmToken = account.token
        crmUser = account.name
        crmEmail = account.email
        cache = ""
    }

    /** Cierra la cuenta activa y la quita del teléfono; si hay otra guardada, queda esa. */
    fun signOut() {
        val remaining = accounts.filterNot { it.token == crmToken }
        saveAccounts(remaining)
        val next = remaining.firstOrNull()
        if (next != null) {
            use(next)
        } else {
            crmToken = ""
            crmUser = ""
            cache = ""
        }
    }

    /** «Ver de todos» (solo equipo de ELROI): todas las piezas pendientes, no solo las tuyas. */
    var showAll: Boolean
        get() = sp.getBoolean("showAll", false)
        set(v) = sp.edit().putBoolean("showAll", v).apply()

    /** API propia (opcional, sin CRM). */
    var apiUrl: String
        get() = sp.getString("apiUrl", "") ?: ""
        set(v) = sp.edit().putString("apiUrl", v.trim()).apply()

    var apiToken: String
        get() = sp.getString("apiToken", "") ?: ""
        set(v) = sp.edit().putString("apiToken", v.trim()).apply()

    var cache: String
        get() = sp.getString("cache", "") ?: ""
        set(v) = sp.edit().putString("cache", v).apply()

    /** Subidas al CRM que no han terminado (sobreviven a cerrar la app). */
    var pendingUploads: String
        get() = sp.getString("pendingUploads", "[]") ?: "[]"
        set(v) = sp.edit().putString("pendingUploads", v).apply()

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

    /** Conservar la copia en el celular después de que el CRM confirma la subida. */
    var keepLocal: Boolean
        get() = sp.getBoolean("keepLocal", false)
        set(v) = sp.edit().putBoolean("keepLocal", v).apply()

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
        { "orden": 5, "texto": "Inicia sesión con tu usuario del CRM en Ajustes y aquí te aparecen tus guiones.", "duracion": 5 }
      ]
    }
  ]
}
""".trimIndent()
