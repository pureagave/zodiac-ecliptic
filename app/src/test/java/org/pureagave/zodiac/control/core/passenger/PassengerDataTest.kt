package org.pureagave.zodiac.control.core.passenger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.pureagave.zodiac.control.core.geo.PlayaPoint
import org.pureagave.zodiac.control.core.navigation.ClockTime
import org.pureagave.zodiac.control.core.navigation.NavigationCue
import org.pureagave.zodiac.control.core.ops.PlayaPoi
import org.pureagave.zodiac.control.core.ops.PoiKind
import org.pureagave.zodiac.control.core.telemetry.Odometer

class PassengerDataTest {
    @Test
    fun a_tablet_hearing_nothing_still_has_the_sun_card() {
        // Sunrise/sunset is a local calculation — no beacon, no network. It's
        // the floor that keeps a lone tablet from showing a blank screen.
        val cards = availablePassengerCards(PassengerInputs())

        assertEquals(listOf(PassengerCard.SUN), cards)
    }

    @Test
    fun cards_without_data_are_dropped_rather_than_shown_empty() {
        val cards = availablePassengerCards(PassengerInputs(hasFix = true, hasAudio = true))

        assertTrue(cards.contains(PassengerCard.WHERE))
        assertTrue(cards.contains(PassengerCard.AUDIO))
        assertFalse("no odometer -> no trip card", cards.contains(PassengerCard.TRIP))
        assertFalse("art embargoed -> no art card", cards.contains(PassengerCard.ART))
    }

    @Test
    fun souls_needs_a_genuinely_live_vision_feed() {
        // The demo threat source exists so the driver's HUD is never blank.
        // Showing that synthetic crowd to passengers as "souls detected" would
        // be inventing people, which is the one thing this project won't do.
        val demoOnly = availablePassengerCards(PassengerInputs(visionLive = false))
        assertFalse(demoOnly.contains(PassengerCard.SOULS))

        val live = availablePassengerCards(PassengerInputs(visionLive = true))
        assertTrue(live.contains(PassengerCard.SOULS))
    }

    @Test
    fun souls_leaves_the_rotation_when_the_rig_is_wholly_blind() {
        // The bug this pins, measured on the fleet 2026-08-25: with every
        // camera unplugged the edge box stays LIVE at 10 Hz and emits a bare
        // ZCOVER (nothing covered), so the card showed "SOULS DETECTED 0 —
        // THERMAL, ALL ROUND THE VEHICLE" — a confident full-circle all-clear
        // from a rig that could see nothing. Liveness alone is not enough.
        val blind =
            availablePassengerCards(
                PassengerInputs(visionLive = true, visionCoverage = emptyList()),
            )

        assertFalse(blind.contains(PassengerCard.SOULS))
    }

    @Test
    fun souls_survives_a_partly_blind_rig() {
        // One camera down is not no cameras: the remaining arc is real, so the
        // card still has something honest to say. A gate that dropped on *any*
        // blind arc would take the card away for the normal fleet case, where
        // the ring never covers the full circle.
        val partial =
            availablePassengerCards(
                PassengerInputs(visionLive = true, visionCoverage = listOf(-64f..65f)),
            )

        assertTrue(partial.contains(PassengerCard.SOULS))
    }

    @Test
    fun souls_stays_when_there_is_no_coverage_signal_at_all() {
        // Null is not empty. An edge box too old to emit ZCOVER, or a signal
        // gone stale, leaves the surround ring on its static covered-arc
        // fallback — so the rig is presumed watching and the card stays.
        // Reading null as "blind" would blank the card for a rig that is in
        // fact looking, which is the opposite failure.
        val noSignal =
            availablePassengerCards(
                PassengerInputs(visionLive = true, visionCoverage = null),
            )

        assertTrue(noSignal.contains(PassengerCard.SOULS))
    }

    @Test
    fun the_rig_watch_predicate_reads_coverage_the_way_the_ring_does() {
        assertTrue("null = static-arc fallback, presumed watching", rigWatchesSomething(null))
        assertFalse("empty = live feed watching nothing", rigWatchesSomething(emptyList()))
        assertTrue("real arcs", rigWatchesSomething(listOf(-64f..65f)))
    }

    @Test
    fun the_full_bus_lights_up_every_card() {
        val cards =
            availablePassengerCards(
                PassengerInputs(
                    hasFix = true,
                    hasAudio = true,
                    visionLive = true,
                    hasShock = true,
                    odometer = Odometer(tripMeters = 1.0, totalMeters = 2.0),
                    artAhead = 3,
                    hasMap = true,
                    hasBeacon = true,
                ),
            )

        assertEquals(PassengerCard.entries.toList(), cards)
    }

    @Test
    fun map_requires_both_fix_and_map() {
        // Full 2x2 truth table: an `||` mutant and a dropped-`hasMap` mutant
        // each fail a different one of these rows.
        assertFalse(
            availablePassengerCards(PassengerInputs(hasFix = false, hasMap = false)).contains(PassengerCard.MAP),
        )
        assertFalse(
            availablePassengerCards(PassengerInputs(hasFix = true, hasMap = false)).contains(PassengerCard.MAP),
        )
        assertFalse(
            availablePassengerCards(PassengerInputs(hasFix = false, hasMap = true)).contains(PassengerCard.MAP),
        )
        assertTrue(
            availablePassengerCards(PassengerInputs(hasFix = true, hasMap = true)).contains(PassengerCard.MAP),
        )
    }

    @Test
    fun telemetry_available_with_fix_only_beacon_only_both_or_neither() {
        // Full 2x2: an `&&` mutant fails the single-source rows, a
        // hardcoded-availability mutant fails "neither".
        assertFalse(
            "neither fix nor beacon -> no telemetry card",
            availablePassengerCards(PassengerInputs(hasFix = false, hasBeacon = false))
                .contains(PassengerCard.TELEMETRY),
        )
        assertTrue(
            "fix only -> telemetry card",
            availablePassengerCards(PassengerInputs(hasFix = true, hasBeacon = false))
                .contains(PassengerCard.TELEMETRY),
        )
        assertTrue(
            "beacon only -> telemetry card",
            availablePassengerCards(PassengerInputs(hasFix = false, hasBeacon = true))
                .contains(PassengerCard.TELEMETRY),
        )
        assertTrue(
            "both -> telemetry card",
            availablePassengerCards(PassengerInputs(hasFix = true, hasBeacon = true))
                .contains(PassengerCard.TELEMETRY),
        )
    }

    @Test
    fun telemetry_rotates_after_trip() {
        // Explicit literal, not PassengerCard.entries.toList() (that's
        // the_full_bus_lights_up_every_card's job) — this pins the concrete
        // position: right after TRIP, before SUN. Also double-checks the
        // PASSENGER-UI MAP position (right after WHERE) survived the merge.
        val cards =
            availablePassengerCards(
                PassengerInputs(
                    hasFix = true,
                    hasAudio = true,
                    visionLive = true,
                    hasShock = true,
                    odometer = Odometer(1.0, 2.0),
                    artAhead = 1,
                    hasMap = true,
                    hasBeacon = true,
                ),
            )

        assertEquals(
            listOf(
                PassengerCard.WHERE,
                PassengerCard.MAP,
                PassengerCard.AUDIO,
                PassengerCard.SOULS,
                PassengerCard.BUMP,
                PassengerCard.TRIP,
                PassengerCard.TELEMETRY,
                PassengerCard.SUN,
                PassengerCard.ART,
            ),
            cards,
        )
    }

    @Test
    fun map_rotates_directly_after_where() {
        // Pins the enum-order decision: address, then the map of it.
        val cards =
            availablePassengerCards(
                PassengerInputs(
                    hasFix = true,
                    hasAudio = true,
                    visionLive = true,
                    hasShock = true,
                    odometer = Odometer(1.0, 2.0),
                    artAhead = 1,
                    hasMap = true,
                ),
            )

        assertEquals(listOf(PassengerCard.WHERE, PassengerCard.MAP), cards.take(2))
    }

    @Test
    fun card_order_is_stable_regardless_of_which_data_arrives() {
        // The rotation indexes into this list; a set that reordered itself as
        // sources came and went would make the display jump around.
        val cards =
            availablePassengerCards(
                PassengerInputs(hasFix = true, visionLive = true, odometer = Odometer(1.0, 2.0)),
            )

        assertEquals(
            listOf(
                PassengerCard.WHERE,
                PassengerCard.SOULS,
                PassengerCard.TRIP,
                PassengerCard.TELEMETRY,
                PassengerCard.SUN,
            ),
            cards,
        )
    }

    @Test
    fun the_location_line_prefers_a_real_street_name() {
        assertEquals("ESPLANADE", passengerLocationLine(NavigationCue.OnArc("Esplanade", ClockTime(3, 0))))
    }

    @Test
    fun off_street_says_something_a_passenger_can_use() {
        assertEquals("OPEN PLAYA", passengerLocationLine(NavigationCue.Unknown))
        assertEquals(
            "HEADING FOR 4:30",
            passengerLocationLine(NavigationCue.TowardClock(ClockTime(4, 30), distanceM = 900.0)),
        )
        assertEquals(
            "DEEP PLAYA OFF 2:00",
            passengerLocationLine(NavigationCue.AwayFromClock(ClockTime(2, 0), distanceM = 400.0)),
        )
    }

    // --- art proximity -----------------------------------------------------

    @Test
    fun art_without_coordinates_is_not_counted_as_nearby() {
        // Real case, 2026-08-08: the BM API is serving 2026 art names with no
        // placements yet. Counting them would invent proximity we cannot know.
        val unplaced = listOf(art("Electric Dandelion", point = null), art("Nova", point = null))

        assertTrue(artNearby(unplaced, ego = PlayaPoint(0.0, 0.0)).isEmpty())
    }

    @Test
    fun theme_camps_are_not_counted_as_art() {
        val mixed = listOf(art("Nova", PlayaPoint(10.0, 0.0)), camp("Nebula Camp", PlayaPoint(20.0, 0.0)))

        val nearby = artNearby(mixed, ego = PlayaPoint(0.0, 0.0))

        assertEquals(listOf("Nova"), nearby.map { it.name })
    }

    @Test
    fun nearby_means_nearby_and_is_ordered_by_distance() {
        val pieces =
            listOf(
                art("Far", PlayaPoint(ART_NEARBY_RADIUS_M + 1.0, 0.0)),
                art("Middle", PlayaPoint(500.0, 0.0)),
                art("Close", PlayaPoint(50.0, 0.0)),
            )

        val nearby = artNearby(pieces, ego = PlayaPoint(0.0, 0.0))

        assertEquals(listOf("Close", "Middle"), nearby.map { it.name })
    }

    @Test
    fun without_a_fix_nothing_is_nearby() {
        val pieces = listOf(art("Nova", PlayaPoint(10.0, 0.0)))

        assertTrue(artNearby(pieces, ego = null).isEmpty())
    }

    private fun art(
        name: String,
        point: PlayaPoint?,
    ) = PlayaPoi(uid = name, name = name, kind = PoiKind.ART, point = point, subtitle = "")

    private fun camp(
        name: String,
        point: PlayaPoint?,
    ) = PlayaPoi(uid = name, name = name, kind = PoiKind.CAMP, point = point, subtitle = "")
}
