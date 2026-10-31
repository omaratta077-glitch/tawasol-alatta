package com.alatta.nativeapp

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.chrono.HijrahDate
import java.time.format.DateTimeFormatter
import java.util.Locale

class PrayerCalculationTest {
    private val raleigh = PrayerPlace("Raleigh", 35.7750, -78.6336, "America/New_York", "NORTH_AMERICA")
    private val configured = PrayerSettings(place = raleigh, confirmedPlace = true, enabled = true, hanafi = true)

    @Test fun reproducesUpstreamPublishedNorthAmericaFixture() {
        // من الاختبار PrayerTimesTest.testPrayerTimes لتطبيق Batoul Apps، فرع Java، بتاريخ 2015-07-12.
        val formatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT).withZone(ZoneId.of(raleigh.zone))
        val times = PrayerCalculation.day(configured, LocalDate.of(2015, 7, 12))
        assertEquals(listOf("04:42", "06:08", "13:21", "18:22", "20:32", "21:57"), times.map { formatter.format(Instant.ofEpochMilli(it.at)) })
    }

    @Test fun afterIshaSchedulesSelectedPrayerOnNextLocalDay() {
        val now = LocalDate.of(2015, 7, 12).atTime(22, 0).atZone(ZoneId.of(raleigh.zone)).toInstant().toEpochMilli()
        val next = PrayerCalculation.next(configured.copy(prayers = setOf("isha")), now, true)!!
        assertEquals("isha", next.id)
        assertEquals(LocalDate.of(2015, 7, 13), Instant.ofEpochMilli(next.at).atZone(ZoneId.of(raleigh.zone)).toLocalDate())
        assertTrue(next.at > now)
    }

    @Test fun sunriseIsNeverAnAdhanAndDisabledOrUnconfirmedSettingsNeverSchedule() {
        val now = Instant.parse("2026-10-21T00:00:00Z").toEpochMilli()
        assertNull(PrayerCalculation.next(configured.copy(prayers = setOf("sunrise")), now, true))
        assertNull(PrayerCalculation.next(configured.copy(prayers = emptySet()), now, true))
        assertNull(PrayerCalculation.next(configured.copy(enabled = false), now, true))
        assertNull(PrayerCalculation.next(configured.copy(confirmedPlace = false), now, true))
        val afterFajr = LocalDate.of(2015, 7, 12).atTime(5, 30).atZone(ZoneId.of(raleigh.zone)).toInstant().toEpochMilli()
        assertEquals("dhuhr", PrayerCalculation.next(configured, afterFajr, true)!!.id)
    }

    @Test fun cityTimezoneTracksCairoDaylightSaving() {
        val place = prayerPlaces.first { it.name == "القاهرة" }
        val settings = PrayerSettings(place = place)
        val summer = PrayerCalculation.day(settings, LocalDate.of(2026, 6, 1)).first().at
        val winter = PrayerCalculation.day(settings, LocalDate.of(2026, 12, 1)).first().at
        assertEquals(3 * 3600, Instant.ofEpochMilli(summer).atZone(ZoneId.of(place.zone)).offset.totalSeconds)
        assertEquals(2 * 3600, Instant.ofEpochMilli(winter).atZone(ZoneId.of(place.zone)).offset.totalSeconds)
    }

    @Test fun manualAdjustmentsAffectOnlyTheirPrayerAndAreBounded() {
        val date = LocalDate.of(2015, 7, 12)
        val base = PrayerCalculation.day(configured, date)
        val adjusted = PrayerCalculation.day(configured.copy(offsets = mapOf("fajr" to 1000)), date)
        assertEquals(30 * 60_000L, adjusted.first().at - base.first().at)
        assertEquals(base.drop(1), adjusted.drop(1))
    }

    @Test fun ummAlQuraAddsRamadanIshaInterval() {
        val settings = PrayerSettings(place = prayerPlaces.first())
        fun interval(month: Int): Long {
            val day = PrayerCalculation.day(settings, LocalDate.from(HijrahDate.of(1447, month, 10)))
            return day.first { it.id == "isha" }.at - day.first { it.id == "maghrib" }.at
        }
        assertEquals(120 * 60_000L, interval(9))
        assertEquals(90 * 60_000L, interval(10))
    }

    @Test fun invalidCoordinatesAndZoneCannotProduceFakeTimes() {
        for (place in listOf(raleigh.copy(latitude = Double.NaN), raleigh.copy(longitude = 200.0), raleigh.copy(zone = "not-a-zone"))) {
            assertTrue(runCatching { PrayerCalculation.day(configured.copy(place = place), LocalDate.of(2026, 10, 21)) }.isFailure)
        }
    }

    @Test fun everyOfferedCalculationMethodProducesSixOrderedTimes() {
        for (method in prayerMethods.keys) {
            val times = PrayerCalculation.day(configured.copy(method = method), LocalDate.of(2026, 10, 21))
            assertEquals(6, times.size)
            assertTrue(method, times.zipWithNext().all { (first, second) -> first.at < second.at })
            assertTrue(method, times.all { it.at % 60_000L == 0L })
        }
    }

    @Test fun outdatedEarlyLateDisabledAndUnselectedAlarmTicketsAreRejected() {
        val ticket = PrayerAlarmTicket("fajr", 1_000_000L, "first-generation")
        assertTrue(acceptsPrayerAlarm(configured, ticket, ticket, ticket.at, true))
        assertFalse(acceptsPrayerAlarm(configured, ticket, ticket.copy(generation = "new-generation"), ticket.at, true))
        assertFalse(acceptsPrayerAlarm(configured, ticket, ticket, ticket.at - 1, true))
        assertFalse(acceptsPrayerAlarm(configured, ticket, ticket, ticket.at + 120_001, true))
        assertFalse(acceptsPrayerAlarm(configured.copy(enabled = false), ticket, ticket, ticket.at, true))
        assertFalse(acceptsPrayerAlarm(configured.copy(prayers = setOf("isha")), ticket, ticket, ticket.at, true))
        assertFalse(acceptsPrayerAlarm(configured, ticket, ticket, ticket.at, false))
        assertFalse(acceptsPrayerAlarm(configured, ticket.copy(generation = ""), ticket.copy(generation = ""), ticket.at, true))
        assertFalse(acceptsPrayerAlarm(configured, ticket.copy(prayer = "sunrise"), ticket.copy(prayer = "sunrise"), ticket.at, true))
    }

    @Test fun catalogHasStableUniqueCountersAndPositiveGoals() {
        assertEquals(6, dhikrGroups.map { it.id }.toSet().size)
        for (group in dhikrGroups) {
            assertEquals(group.entries.size, group.entries.map { it.id }.toSet().size)
            assertTrue(group.entries.all { it.target > 0 && it.text.isNotBlank() && it.source.isNotBlank() })
        }
    }

    @Test fun morningAndEveningStartWithRequestedVersesAndSalawatPrecedesTawhid() {
        for (id in listOf("morning", "evening")) {
            val entries = dhikrGroups.first { it.id == id }.entries
            assertEquals(listOf("fatiha", "kursi", "baqarah-ending"), entries.take(3).map { it.id })
            val salawat = entries.first { it.id == "salawat-ten" }
            assertEquals(10, salawat.target)
            assertTrue(salawat.text.contains("صَلِّ وَسَلِّمْ وَبَارِكْ عَلَى سَيِّدِنَا مُحَمَّدٍ"))
            assertTrue(entries.indexOf(salawat) < entries.indexOfFirst { it.id == "kingdom" })
        }
    }

    @Test fun allSingleReadCardsNowHaveThreeCountGoalWithoutDuplicatingEntries() {
        for (group in dhikrGroups) {
            assertTrue(group.id, group.entries.all { it.target >= 3 })
            assertEquals(group.id, group.entries.size, group.entries.map { it.id }.distinct().size)
        }
        val morning = dhikrGroups.single { it.id == "morning" }.entries
        assertEquals(3, morning.single { it.id == "all-good" }.target)
        assertTrue(morning.single { it.id == "all-good" }.source.contains("3846"))
        assertTrue(morning.single { it.id == "all-good" }.text.contains("كُلِّهِ"))
        val beloved = morning.single { it.id == "beloved-words" }
        assertEquals(3, beloved.target)
        assertTrue(beloved.text.contains("الْعَظِيمِ، سُبْحَانَ اللَّهِ وَبِحَمْدِهِ"))
        assertTrue(beloved.note.contains("الكلمتين معًا"))
        assertEquals(10, morning.single { it.id == "salawat-ten" }.target)
        assertEquals(100, morning.single { it.id == "tasbih" }.target)
    }

    @Test fun abuDardaPrayerIsPresentInBothDailyGroupsAndClearlyMarkedWeak() {
        for (id in listOf("morning", "evening")) {
            val list = dhikrGroups.single { it.id == id }.entries
            val prayer = list.single { it.id == "trust-refuge-abu-darda" }
            assertEquals(3, prayer.target)
            assertTrue(prayer.text.contains("الْعَلِيِّ الْعَظِيمِ"))
            assertTrue(prayer.text.contains("أَنْتَ آخِذٌ بِنَاصِيَتِهَا"))
            assertTrue(prayer.source.contains("ضعيف"))
            assertTrue(prayer.note.contains("لا يثبت له عدد مخصوص"))
            val comprehensive = list.single { it.id == "all-good" }
            assertTrue(comprehensive.note.contains("ما عاذ به"))
        }
    }

    @Test fun singleReadTargetsBecomeThreeOnlyForReaderWithoutChangingSource() {
        val entry = Dhikr("sample", "title", "text", 1, "source")
        val three = entry.forReader()
        assertEquals(1, entry.target)
        assertEquals(3, three.target)
        assertEquals(entry.text, three.text)
        assertEquals(entry.source, three.source)
        assertTrue(three.note.contains("اختياري"))
        assertEquals(7, entry.copy(target = 7).forReader().target)
    }

    @Test fun ArabicSearchFindsUnvowelledAndHamzaVariants() {
        assertTrue(matchesDhikrQuery("أَسْتَغْفِرُ اللَّهَ", "استغفر"))
        assertTrue(matchesDhikrQuery("سُبْحَانَ اللَّهِ وَبِحَمْدِهِ", "سبحان الله"))
        assertFalse(matchesDhikrQuery("أذكار الصباح", "المساء"))
    }
}
