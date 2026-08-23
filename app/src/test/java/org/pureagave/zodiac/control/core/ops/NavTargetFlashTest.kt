package org.pureagave.zodiac.control.core.ops

import org.junit.Assert.assertEquals
import org.junit.Test
import org.pureagave.zodiac.control.core.navigation.ClockTime

class NavTargetFlashTest {
    @Test
    fun presetHome_routesToHome() {
        assertEquals(NavTargetFlash("ROUTING TO", "HOME"), navTargetFlashOf(NavSharePayload.Preset(NavTarget.HOME)))
    }

    @Test
    fun presetMan_routesToTheMan() {
        // Fails if this reuses NavTarget.MAN.label ("MAN") instead of the sentence form.
        assertEquals(NavTargetFlash("ROUTING TO", "THE MAN"), navTargetFlashOf(NavSharePayload.Preset(NavTarget.MAN)))
    }

    @Test
    fun presetTemple_routesToTheTemple() {
        assertEquals(NavTargetFlash("ROUTING TO", "THE TEMPLE"), navTargetFlashOf(NavSharePayload.Preset(NavTarget.TEMPLE)))
    }

    @Test
    fun address_formatsClockZeroPadded_andJoinsWithRing() {
        // Minutes < 10: fails on a missing zero-pad, missing " & " separator, or extra spaces.
        assertEquals(
            NavTargetFlash("ROUTING TO", "4:05 & K"),
            navTargetFlashOf(NavSharePayload.Address(ClockTime(4, 5), "K")),
        )
    }

    @Test
    fun bath_routesToNearestToilet() {
        assertEquals(NavTargetFlash("ROUTING TO", "NEAREST TOILET"), navTargetFlashOf(NavSharePayload.Bath))
    }

    @Test
    fun clear_isRouteCleared_notRoutingTo() {
        // Fails if Clear gets mapped as though it were a routing target.
        assertEquals(NavTargetFlash("ROUTE", "CLEARED"), navTargetFlashOf(NavSharePayload.Clear))
    }
}
