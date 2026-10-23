package org.pureagave.zodiac.control.core.ops

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.pureagave.zodiac.control.core.model.CockpitConcept

class OverlayPriorityTest {
    @Test
    fun driver_collision_banner_hides_the_street_flash() {
        assertFalse(streetPopupVisible(CockpitConcept.DRIVER, brakeAdvised = true))
    }

    @Test
    fun driver_without_a_brake_alert_shows_the_street_flash() {
        assertTrue(streetPopupVisible(CockpitConcept.DRIVER, brakeAdvised = false))
    }

    @Test
    fun concepts_without_a_centre_banner_always_show_the_street_flash() {
        for (concept in CockpitConcept.entries.filter { it != CockpitConcept.DRIVER }) {
            assertTrue(concept.name, streetPopupVisible(concept, brakeAdvised = true))
        }
    }
}
