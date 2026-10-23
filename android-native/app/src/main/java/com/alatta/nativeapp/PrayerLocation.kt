package com.alatta.nativeapp

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.time.ZoneId
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Foreground, bounded location lookup. No Google Play Services or background tracking. */
internal object PrayerLocation {
    fun permitted(context: Context) = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    suspend fun current(context: Context): PrayerPlace {
        check(permitted(context)) { "اسمح بالموقع لتحديد مواقيت الصلاة، أو اختر المدينة يدويًا" }
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val providers = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER).filter {
            (it != LocationManager.GPS_PROVIDER || fine) && runCatching { manager.isProviderEnabled(it) }.getOrDefault(false)
        }
        check(providers.isNotEmpty()) { "شغّل موقع الهاتف، أو اختر المدينة يدويًا" }
        fun usable(location: Location) = validPrayerLocation(location.latitude, location.longitude,
            location.accuracy.toDouble(), (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000L)
        val cached = providers.mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .filter(::usable).minByOrNull { it.accuracy }
        val fix = cached ?: withTimeoutOrNull(30_000L) {
            suspendCancellableCoroutine<Location> { continuation ->
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        if (usable(location) && continuation.isActive) { manager.removeUpdates(this); continuation.resume(location) }
                    }
                    override fun onProviderEnabled(provider: String) = Unit
                    override fun onProviderDisabled(provider: String) = Unit
                    @Deprecated("Legacy Android callback")
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
                }
                continuation.invokeOnCancellation { manager.removeUpdates(listener) }
                var registered = false
                providers.forEach { provider ->
                    if (runCatching { manager.requestLocationUpdates(provider, 1000L, 0f, listener, Looper.getMainLooper()) }.isSuccess) registered = true
                }
                if (!registered && continuation.isActive) continuation.resumeWithException(IllegalStateException("تعذر الحصول على الموقع"))
            }
        } ?: error("تعذر تحديد الموقع الآن. جرّب قرب نافذة، أو اختر المدينة يدويًا")
        val zone = ZoneId.systemDefault().id
        val method = prayerPlaces.firstOrNull { it.zone == zone }?.method ?: "MUSLIM_WORLD_LEAGUE"
        return PrayerPlace("موقعي الحالي", fix.latitude, fix.longitude, zone, method).validate()
    }

    suspend fun refreshIfEnabled(context: Context) {
        val settings = PrayerStore.read(context)
        val prefs = PrayerStore.prefs(context)
        if (!settings.automaticLocation || !permitted(context) ||
            (System.currentTimeMillis() - prefs.getLong("location_updated_at", 0L) in 0L..1_800_000L && settings.place.zone == ZoneId.systemDefault().id)) return
        try {
            val place = current(context)
            // Re-read after the asynchronous fix: never restore a setting disabled during lookup.
            val latest = PrayerStore.read(context)
            if (latest.automaticLocation) {
                AdhanScheduler.update(context, latest.copy(place = place, confirmedPlace = true))
                prefs.edit().putLong("location_updated_at", System.currentTimeMillis()).apply()
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Keep the last confirmed place; manual selection remains available. */ }
    }
}

internal fun validPrayerLocation(latitude: Double, longitude: Double, accuracyMeters: Double, ageMillis: Long): Boolean =
    latitude.isFinite() && latitude in -89.0..89.0 && longitude.isFinite() && longitude in -180.0..180.0 &&
        accuracyMeters.isFinite() && accuracyMeters in 0.0..25_000.0 && ageMillis in 0L..600_000L
