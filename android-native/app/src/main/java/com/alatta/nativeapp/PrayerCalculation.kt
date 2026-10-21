package com.alatta.nativeapp

import com.batoulapps.adhan.CalculationMethod
import com.batoulapps.adhan.Coordinates
import com.batoulapps.adhan.Madhab
import com.batoulapps.adhan.PrayerTimes
import com.batoulapps.adhan.data.DateComponents
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.chrono.HijrahDate
import java.time.temporal.ChronoField

internal data class PrayerPlace(
    val name: String, val latitude: Double, val longitude: Double,
    val zone: String, val method: String = "UMM_AL_QURA"
) {
    fun validate(): PrayerPlace {
        require(name.isNotBlank() && name.length <= 80) { "اكتب اسم المدينة" }
        require(latitude.isFinite() && latitude in -89.0..89.0 && longitude.isFinite() && longitude in -180.0..180.0) { "إحداثيات المدينة غير صحيحة" }
        require(zone in ZoneId.getAvailableZoneIds()) { "اختر منطقة زمنية صحيحة مثل Asia/Riyadh" }
        return this
    }
}

internal val prayerPlaces = listOf(
    PrayerPlace("مكة المكرمة", 21.4225, 39.8262, "Asia/Riyadh"),
    PrayerPlace("المدينة المنورة", 24.4672, 39.6111, "Asia/Riyadh"),
    PrayerPlace("الرياض", 24.7136, 46.6753, "Asia/Riyadh"),
    PrayerPlace("جدة", 21.4858, 39.1925, "Asia/Riyadh"),
    PrayerPlace("الدمام", 26.4207, 50.0888, "Asia/Riyadh"),
    PrayerPlace("القاهرة", 30.0444, 31.2357, "Africa/Cairo", "EGYPTIAN"),
    PrayerPlace("الإسكندرية", 31.2001, 29.9187, "Africa/Cairo", "EGYPTIAN"),
    PrayerPlace("المنيا", 28.1099, 30.7503, "Africa/Cairo", "EGYPTIAN"),
    PrayerPlace("بني سويف", 29.0661, 31.0994, "Africa/Cairo", "EGYPTIAN"),
    PrayerPlace("دبي", 25.2048, 55.2708, "Asia/Dubai", "DUBAI"),
    PrayerPlace("أبوظبي", 24.4539, 54.3773, "Asia/Dubai", "DUBAI"),
    PrayerPlace("الكويت", 29.3759, 47.9774, "Asia/Kuwait", "KUWAIT"),
    PrayerPlace("الدوحة", 25.2854, 51.5310, "Asia/Qatar", "QATAR"),
    PrayerPlace("المنامة", 26.2285, 50.5860, "Asia/Bahrain"),
    PrayerPlace("مسقط", 23.5880, 58.3829, "Asia/Muscat", "MUSLIM_WORLD_LEAGUE"),
    PrayerPlace("عمّان", 31.9539, 35.9106, "Asia/Amman", "MUSLIM_WORLD_LEAGUE"),
    PrayerPlace("القدس", 31.7683, 35.2137, "Asia/Jerusalem", "MUSLIM_WORLD_LEAGUE"),
    PrayerPlace("بغداد", 33.3152, 44.3661, "Asia/Baghdad", "MUSLIM_WORLD_LEAGUE"),
    PrayerPlace("دمشق", 33.5138, 36.2765, "Asia/Damascus", "MUSLIM_WORLD_LEAGUE"),
    PrayerPlace("إسطنبول", 41.0082, 28.9784, "Europe/Istanbul", "MUSLIM_WORLD_LEAGUE"),
    PrayerPlace("لندن", 51.5074, -0.1278, "Europe/London", "MUSLIM_WORLD_LEAGUE"),
    PrayerPlace("نيويورك", 40.7128, -74.0060, "America/New_York", "NORTH_AMERICA")
)

internal val prayerNames = linkedMapOf("fajr" to "الفجر", "sunrise" to "الشروق", "dhuhr" to "الظهر", "asr" to "العصر", "maghrib" to "المغرب", "isha" to "العشاء")
internal val alarmPrayerIds = prayerNames.keys.filter { it != "sunrise" }.toSet()
internal val prayerMethods = linkedMapOf(
    "UMM_AL_QURA" to "أم القرى — مكة المكرمة", "EGYPTIAN" to "الهيئة المصرية للمساحة",
    "MUSLIM_WORLD_LEAGUE" to "رابطة العالم الإسلامي", "KARACHI" to "جامعة كراتشي",
    "DUBAI" to "الإمارات", "KUWAIT" to "الكويت", "QATAR" to "قطر",
    "NORTH_AMERICA" to "أمريكا الشمالية"
)

internal data class PrayerSettings(
    val place: PrayerPlace = prayerPlaces[2], val confirmedPlace: Boolean = false,
    val enabled: Boolean = false, val method: String = place.method,
    val hanafi: Boolean = false, val prayers: Set<String> = alarmPrayerIds,
    val offsets: Map<String, Int> = emptyMap(), val soundUri: String = ""
)

internal data class PrayerMoment(val id: String, val at: Long) {
    val title: String get() = prayerNames.getValue(id)
}

internal data class PrayerAlarmTicket(val prayer: String, val at: Long, val generation: String)

internal fun acceptsPrayerAlarm(settings: PrayerSettings, ticket: PrayerAlarmTicket, expected: PrayerAlarmTicket,
                                now: Long, notificationsAllowed: Boolean): Boolean =
    settings.enabled && settings.confirmedPlace && notificationsAllowed && ticket.prayer in alarmPrayerIds &&
        ticket.prayer in settings.prayers && ticket.generation.isNotBlank() && ticket == expected &&
        now - ticket.at in 0L..120_000L

/** Local astronomical calculation; coordinates and reminder choices never leave the device. */
internal object PrayerCalculation {
    fun day(settings: PrayerSettings, date: LocalDate): List<PrayerMoment> {
        val place = settings.place.validate()
        require(settings.method in prayerMethods) { "اختر طريقة حساب المواقيت" }
        val parameters = CalculationMethod.valueOf(settings.method).parameters.apply {
            madhab = if (settings.hanafi) Madhab.HANAFI else Madhab.SHAFI
            // Umm al-Qura uses a 120-minute Isha interval in Ramadan.
            if (settings.method == "UMM_AL_QURA" && HijrahDate.from(date).get(ChronoField.MONTH_OF_YEAR) == 9) ishaInterval = 120
            adjustments.fajr = settings.offsets["fajr"]?.coerceIn(-30, 30) ?: 0
            adjustments.dhuhr = settings.offsets["dhuhr"]?.coerceIn(-30, 30) ?: 0
            adjustments.asr = settings.offsets["asr"]?.coerceIn(-30, 30) ?: 0
            adjustments.maghrib = settings.offsets["maghrib"]?.coerceIn(-30, 30) ?: 0
            adjustments.isha = settings.offsets["isha"]?.coerceIn(-30, 30) ?: 0
        }
        val times = PrayerTimes(Coordinates(place.latitude, place.longitude), DateComponents(date.year, date.monthValue, date.dayOfMonth), parameters)
        val values = listOf(times.fajr, times.sunrise, times.dhuhr, times.asr, times.maghrib, times.isha)
        return prayerNames.keys.zip(values).map { (id, value) ->
            // The Java library rounds seconds but retains Calendar's current
            // milliseconds. Strip those so results and alarms are stable minutes.
            val instant = requireNotNull(value) { "تعذر حساب المواقيت لهذا الموقع والتاريخ" }.time / 60_000L * 60_000L
            require(instant > 0L) { "تعذر حساب المواقيت لهذا الموقع والتاريخ" }
            PrayerMoment(id, instant)
        }
    }

    fun next(settings: PrayerSettings, now: Long, remindersOnly: Boolean = false): PrayerMoment? {
        if (remindersOnly && (!settings.enabled || !settings.confirmedPlace || settings.prayers.intersect(alarmPrayerIds).isEmpty())) return null
        val date = Instant.ofEpochMilli(now).atZone(ZoneId.of(settings.place.zone)).toLocalDate()
        return (0L..2L).asSequence().flatMap { day(settings, date.plusDays(it)).asSequence() }
            .filter { it.id != "sunrise" && it.at > now && (!remindersOnly || it.id in settings.prayers) }
            .minByOrNull { it.at }
    }
}
