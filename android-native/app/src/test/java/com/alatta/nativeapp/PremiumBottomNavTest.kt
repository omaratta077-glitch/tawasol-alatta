package com.alatta.nativeapp

import org.junit.Assert.*
import org.junit.Test

class PremiumBottomNavTest {
    @Test fun removedTvExperimentNeverAppearsInBottomNavigation() {
        val names = premiumBottomNavDestinations.map { it.first }
        assertFalse(names.contains("العطا TV"))
        assertEquals(1, names.count { it == "الرياضة" })
        assertEquals(names.size, names.distinct().size)
    }

    @Test fun oldTvNavigationCannotOpenAnEmptyScreen() {
        assertEquals("الرياضة", resolvedNativeDestination("العطا TV"))
        assertEquals("الرياضة", resolvedNativeDestination("الرياضة"))
        assertEquals("القرآن", resolvedNativeDestination("القرآن"))
    }
}
