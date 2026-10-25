package com.alatta.nativeapp

import android.content.Context
import android.net.Uri
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import java.io.File
import okhttp3.OkHttpClient

/** Playback cache only. It neither predownloads a feed nor caches live/YouTube streams. */
internal object VideoTransport {
    const val MAX_CACHE_BYTES = 64L * 1024 * 1024
    @Volatile private var sharedFactory: DataSource.Factory? = null
    private var cache: SimpleCache? = null

    // Called on IO when the viewer opens, never during startup or composition.
    fun factory(context: Context, live: Boolean, requestHeaders: Map<String, String> = emptyMap()): DataSource.Factory {
        val app = context.applicationContext
        if (requestHeaders.isNotEmpty()) return DefaultDataSource.Factory(app,
            OkHttpDataSource.Factory(AttaHttp.mediaClient).setDefaultRequestProperties(requestHeaders.toMap()))
        if (live) return DefaultDataSource.Factory(app, OkHttpDataSource.Factory(AttaHttp.mediaClient))
        return sharedFactory ?: synchronized(this) {
            sharedFactory ?: run {
                val saved = try {
                    SimpleCache(File(app.cacheDir, "post-video-bytes"), LeastRecentlyUsedCacheEvictor(MAX_CACHE_BYTES),
                        StandaloneDatabaseProvider(app)).also { cache = it }
                } catch (_: Exception) { null } // Full/unavailable cache must not stop playback.
                routedFactory(app, saved).also { sharedFactory = it }
            }
        }
    }

    internal fun canCache(uri: Uri): Boolean {
        val origin = Uri.parse(ATTA_API_BASE)
        return uri.scheme == "https" && uri.host == origin.host && uri.port in listOf(-1, 443) &&
            uri.path.orEmpty().startsWith("/media/")
    }

    internal fun routedFactory(context: Context, cache: Cache?, client: OkHttpClient = AttaHttp.mediaClient,
        eligible: (Uri) -> Boolean = ::canCache): DataSource.Factory {
        val upstream = DefaultDataSource.Factory(context, OkHttpDataSource.Factory(client))
        if (cache == null) return upstream
        val cached = CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        return DataSource.Factory {
            val network = upstream.createDataSource()
            val local = cached.createDataSource()
            object : DataSource {
                private var active: DataSource? = null
                override fun addTransferListener(listener: TransferListener) {
                    network.addTransferListener(listener); local.addTransferListener(listener)
                }
                override fun open(spec: DataSpec): Long {
                    active = if (eligible(spec.uri)) local else network
                    return requireNotNull(active).open(spec)
                }
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int = requireNotNull(active).read(buffer, offset, length)
                override fun getUri(): Uri? = active?.uri
                override fun getResponseHeaders(): Map<String, List<String>> = active?.responseHeaders ?: emptyMap()
                override fun close() { try { active?.close() } finally { active = null } }
            }
        }
    }

    fun loadControl(live: Boolean): DefaultLoadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(if (live) 2000 else 15000, if (live) 10000 else 40000,
            if (live) 750 else 1000, if (live) 1500 else 2000)
        .setTargetBufferBytes((if (live) 8 else 24) * 1024 * 1024)
        .setPrioritizeTimeOverSizeThresholds(false).build()
}
