package com.alatta.nativeapp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

/** Uploads and legacy feed previews use the same extractor and skip black lead-in frames. */
internal object VideoPostPreview {
    private val memory = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private val decoders = Semaphore(2)
    fun mostlyBlack(frame: Bitmap): Boolean {
        var visible = 0
        for (x in 1..8) for (y in 1..8) {
            val pixel = frame.getPixel((frame.width * x / 9).coerceAtMost(frame.width - 1), (frame.height * y / 9).coerceAtMost(frame.height - 1))
            if (maxOf((pixel shr 16) and 255, (pixel shr 8) and 255, pixel and 255) > 28) visible++
        }
        return visible < 4
    }
    private fun extract(retriever: MediaMetadataRetriever): Bitmap? {
        val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
        val positions = listOf((duration / 10).coerceAtLeast(1000).coerceAtMost((duration - 1).coerceAtLeast(0)), duration / 2, 0L).distinct()
        var fallback: Bitmap? = null
        for (at in positions) {
            val frame = runCatching {
                val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 720
                val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 405
                val scale = 720f / maxOf(width, height, 720)
                if (Build.VERSION.SDK_INT >= 27) retriever.getScaledFrameAtTime(at * 1000, MediaMetadataRetriever.OPTION_CLOSEST,
                    (width * scale).toInt().coerceAtLeast(1), (height * scale).toInt().coerceAtLeast(1))
                else retriever.getFrameAtTime(at * 1000, MediaMetadataRetriever.OPTION_CLOSEST)?.let { original ->
                    val ratio = 720f / maxOf(original.width, original.height, 720)
                    val small = Bitmap.createScaledBitmap(original, (original.width * ratio).toInt().coerceAtLeast(1),
                        (original.height * ratio).toInt().coerceAtLeast(1), true)
                    if (small !== original) original.recycle()
                    small
                }
            }.getOrNull() ?: continue
            if (!mostlyBlack(frame)) { fallback?.recycle(); return frame }
            if (fallback == null) fallback = frame else frame.recycle()
        }
        return fallback
    }

    suspend fun uploadCover(context: Context, uri: Uri): ByteArray? = withContext(Dispatchers.IO) {
        decoders.withPermit {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                extract(retriever)?.let { bitmap ->
                    try { ByteArrayOutputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out); out.toByteArray() } }
                    finally { bitmap.recycle() }
                }
            } finally { retriever.release() }
        }
    }

    suspend fun remote(context: Context, url: String): Bitmap? = withContext(Dispatchers.IO) {
        memory.get(url)?.let { return@withContext it }
        decoders.withPermit {
            memory.get(url)?.let { return@withPermit it }
            val directory = File(context.cacheDir, "video-posters").apply { mkdirs() }
            val key = MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
            val file = File(directory, "$key.jpg")
            BitmapFactory.decodeFile(file.path)?.let { memory.put(url, it); return@withPermit it }
            runCatching {
                val retriever = MediaMetadataRetriever()
                val frame = try { retriever.setDataSource(url, emptyMap()); extract(retriever) } finally { retriever.release() }
                frame?.also { bitmap ->
                    memory.put(url, bitmap)
                    runCatching { file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) } }
                    // Feed thumbnails are disposable; selected Adhan recordings are stored separately.
                    directory.listFiles()?.sortedByDescending { it.lastModified() }?.drop(48)?.forEach { it.delete() }
                }
            }.getOrNull()
        }
    }
}
