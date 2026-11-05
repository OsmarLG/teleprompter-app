package com.osmarlg.teleprompter

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Miniaturas de los videos del CRM: saca un cuadro del video (leyendo solo lo necesario por la liga firmada) y lo
 * guarda en memoria y en el caché del teléfono, para que la segunda vez salga al instante.
 */
object VideoFrames {
    data class Frame(val bitmap: Bitmap, val durationMs: Long)

    private val memory = object : LruCache<String, Frame>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Frame) = value.bitmap.byteCount
    }

    // Pocas a la vez: cada una abre una conexión al servidor.
    private val gate = Semaphore(2)

    fun cached(uuid: String): Frame? = memory.get(uuid)

    suspend fun load(context: Context, uuid: String, url: String): Frame? {
        memory.get(uuid)?.let { return it }
        return gate.withPermit {
            memory.get(uuid) ?: withContext(Dispatchers.IO) {
                runCatching { fromDisk(context, uuid) ?: extract(context, uuid, url) }.getOrNull()
            }?.also { memory.put(uuid, it) }
        }
    }

    /** Miniatura de lo recién grabado que todavía está en el celular (subiendo o en fila). */
    suspend fun loadLocal(context: Context, id: String, isPhoto: Boolean): Frame? {
        val key = "local|$id"
        memory.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                val uri = if (id.startsWith("/")) Uri.fromFile(File(id)) else Uri.parse(id)
                if (isPhoto) {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                    var sample = 1
                    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 480) sample *= 2
                    context.contentResolver.openInputStream(uri)?.use {
                        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
                    }?.let { Frame(it, 0L) }
                } else {
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(context, uri)
                        val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                        retriever.getFrameAtTime(minOf(1_000_000L, duration * 1000 / 3), MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                            ?.let { raw ->
                                val scale = 480f / maxOf(raw.width, raw.height)
                                Frame(if (scale < 1f) Bitmap.createScaledBitmap(raw, (raw.width * scale).toInt(), (raw.height * scale).toInt(), true) else raw, duration)
                            }
                    } finally {
                        runCatching { retriever.release() }
                    }
                }
            }.getOrNull()
        }?.also { memory.put(key, it) }
    }

    private fun dir(context: Context) = File(context.cacheDir, "miniaturas").apply { mkdirs() }

    private fun fromDisk(context: Context, uuid: String): Frame? {
        val image = File(dir(context), "$uuid.jpg")
        if (!image.exists()) return null
        val bitmap = BitmapFactory.decodeFile(image.path) ?: return null
        val duration = File(dir(context), "$uuid.txt").takeIf { it.exists() }?.readText()?.toLongOrNull() ?: 0L
        return Frame(bitmap, duration)
    }

    private fun extract(context: Context, uuid: String, url: String): Frame? {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(url, HashMap())
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            // Un cuadro cerca del primer segundo (el cuadro 0 suele salir negro o a medio enfocar).
            val atUs = minOf(1_000_000L, duration * 1000 / 3).coerceAtLeast(0L)
            val raw = retriever.getFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return null
            val scale = 480f / maxOf(raw.width, raw.height)
            val bitmap = if (scale < 1f) {
                Bitmap.createScaledBitmap(raw, (raw.width * scale).toInt(), (raw.height * scale).toInt(), true)
                    .also { if (it !== raw) raw.recycle() }
            } else {
                raw
            }
            runCatching {
                File(dir(context), "$uuid.jpg").outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 82, it) }
                File(dir(context), "$uuid.txt").writeText(duration.toString())
            }
            return Frame(bitmap, duration)
        } finally {
            runCatching { retriever.release() }
        }
    }
}

/** 47 s → «0:47»; 125 s → «2:05». */
fun formatDuration(ms: Long): String {
    val total = (ms / 1000).toInt()
    return "%d:%02d".format(total / 60, total % 60)
}
