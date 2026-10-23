package com.alatta.nativeapp

import org.junit.Assert.*
import org.junit.Test

class PrayerLocationTest {
    @Test fun acceptsApproximateFreshLocationsForPrayerTimes() {
        assertTrue(validPrayerLocation(24.7136, 46.6753, 5000.0, 30_000))
        assertTrue(validPrayerLocation(30.0444, 31.2357, 25_000.0, 600_000))
    }
    @Test fun staleUnusableAndMalformedLocationsCannotReplaceConfirmedCity() {
        assertFalse(validPrayerLocation(24.7, 46.6, 100.0, 600_001))
        assertFalse(validPrayerLocation(24.7, 46.6, 100.0, -1))
        assertFalse(validPrayerLocation(24.7, 46.6, 25_001.0, 0))
        assertFalse(validPrayerLocation(24.7, 46.6, Double.NaN, 0))
        assertFalse(validPrayerLocation(Double.NaN, 46.6, 10.0, 0))
        assertFalse(validPrayerLocation(90.0, 46.6, 10.0, 0))
        assertFalse(validPrayerLocation(24.7, 181.0, 10.0, 0))
    }
}
