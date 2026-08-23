package org.pureagave.zodiac.control.core.passenger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CardRotationTest {
    private val all = PassengerCard.entries.toList()

    private fun rotation() = CardRotation(dwellMs = DWELL, interruptMs = INTERRUPT)

    @Test
    fun it_holds_a_card_for_the_dwell_then_advances() {
        val r = rotation()

        val first = r.view(0, all)!!.card
        assertEquals(first, r.view(DWELL - 1, all)!!.card)
        assertEquals(all[1], r.view(DWELL, all)!!.card)
    }

    @Test
    fun the_rotation_wraps() {
        val r = rotation()
        r.view(0, all)

        val afterFullLoop = r.view(DWELL * all.size, all)!!.card

        assertEquals(all[0], afterFullLoop)
    }

    @Test
    fun an_event_barges_in_and_then_gives_the_screen_back() {
        val r = rotation()
        r.view(0, all)

        r.interruptWith(PassengerCard.BUMP, nowMs = 1_000)

        val during = r.view(2_000, all)!!
        assertEquals(PassengerCard.BUMP, during.card)
        assertEquals(CardReason.INTERRUPT, during.reason)

        val after = r.view(1_000 + INTERRUPT, all)!!
        assertEquals(CardReason.ROTATION, after.reason)
    }

    @Test
    fun a_repeat_of_the_same_event_re_arms_rather_than_stacking() {
        // A run of bumps should hold the gauge up, not queue seven of them.
        val r = rotation()
        r.interruptWith(PassengerCard.BUMP, nowMs = 0)
        r.interruptWith(PassengerCard.BUMP, nowMs = INTERRUPT - 1)

        assertEquals(PassengerCard.BUMP, r.view(INTERRUPT + 1, all)!!.card)
        assertEquals(CardReason.ROTATION, r.view(INTERRUPT * 2, all)!!.reason)
    }

    @Test
    fun a_newer_event_replaces_an_older_one_immediately() {
        // The passenger just felt the newest thing; showing the previous one
        // would be describing the wrong moment.
        val r = rotation()
        r.interruptWith(PassengerCard.BUMP, nowMs = 0)

        r.interruptWith(PassengerCard.WHERE, nowMs = 1_000)

        assertEquals(PassengerCard.WHERE, r.view(1_500, all)!!.card)
    }

    @Test
    fun the_rotation_resumes_where_it_left_off_after_a_short_interrupt() {
        // A bump must not cost the passenger the card they were mid-way
        // through reading.
        val r = rotation()
        r.view(0, all)
        r.interruptWith(PassengerCard.BUMP, nowMs = 1_000)

        val resumed = r.view(1_000 + INTERRUPT, all)!!

        assertEquals(all[0], resumed.card)
    }

    @Test
    fun a_long_interrupt_lands_the_rotation_where_it_would_have_been() {
        // Not one card per query: a 3-dwell interrupt should skip 3 cards, or
        // the rotation crawls after every long event.
        val r = rotation()
        r.view(0, all)
        val longInterrupt = CardRotation(dwellMs = DWELL, interruptMs = DWELL * 3)
        longInterrupt.view(0, all)
        longInterrupt.interruptWith(PassengerCard.BUMP, nowMs = 0)

        val resumed = longInterrupt.view(DWELL * 3, all)!!

        assertEquals(all[3], resumed.card)
    }

    @Test
    fun only_cards_with_data_are_shown() {
        // Art is embargoed until BM releases 2026 locations; a passenger
        // display cycling through blank cards reads as broken.
        val r = rotation()
        val subset = listOf(PassengerCard.WHERE, PassengerCard.AUDIO)

        assertEquals(PassengerCard.WHERE, r.view(0, subset)!!.card)
        assertEquals(PassengerCard.AUDIO, r.view(DWELL, subset)!!.card)
        assertEquals(PassengerCard.WHERE, r.view(DWELL * 2, subset)!!.card)
    }

    @Test
    fun an_interrupt_for_a_card_with_no_data_is_dropped_not_shown_empty() {
        val r = rotation()
        val subset = listOf(PassengerCard.WHERE)

        r.interruptWith(PassengerCard.BUMP, nowMs = 0)

        val view = r.view(100, subset)!!
        assertEquals(PassengerCard.WHERE, view.card)
        assertEquals(CardReason.ROTATION, view.reason)
    }

    @Test
    fun nothing_available_shows_nothing_rather_than_inventing_a_card() {
        assertNull(rotation().view(0, emptyList()))
    }

    @Test
    fun the_available_set_shrinking_never_indexes_out_of_bounds() {
        // The beacon dropping mid-rotation removes cards underneath us.
        val r = rotation()
        repeat(all.size) { r.view(DWELL * it.toLong(), all) }

        val shrunk = r.view(DWELL * all.size, listOf(PassengerCard.WHERE))

        assertEquals(PassengerCard.WHERE, shrunk!!.card)
    }

    // --- hold while parked -------------------------------------------------

    @Test
    fun holding_keeps_the_current_card_past_its_dwell() {
        // Parked next to a piece of art: passengers finally have time to read
        // the description, so the card must not rotate out from under them.
        val r = rotation()
        val first = r.view(0, all)!!.card

        assertEquals(first, r.view(DWELL * 5, all, hold = RotationHold.SOFT)!!.card)
        assertEquals(first, r.view(DWELL * 20, all, hold = RotationHold.SOFT)!!.card)
    }

    @Test
    fun releasing_the_hold_resumes_the_remaining_dwell_not_an_instant_flip() {
        // Pulling away must not snatch the card at the moment someone looks
        // back up: the held card keeps whatever dwell it had left.
        val r = rotation()
        r.view(0, all)
        r.view(DWELL / 4, all) // a quarter of the dwell has genuinely elapsed
        r.view(DWELL * 10, all, hold = RotationHold.SOFT)

        val justAfterRelease = r.view(DWELL * 10 + 1, all)!!

        assertEquals(all[0], justAfterRelease.card)
    }

    @Test
    fun the_rotation_advances_normally_once_the_hold_ends() {
        val r = rotation()
        r.view(0, all)
        r.view(DWELL * 10, all, hold = RotationHold.SOFT)

        // Enough time after release for the remaining dwell to expire.
        val later = r.view(DWELL * 10 + DWELL + 1, all)!!

        assertEquals(all[1], later.card)
    }

    @Test
    fun an_event_still_interrupts_a_held_card() {
        // Safety-adjacent framing aside, a bump is something the passenger
        // just physically felt; it should still get its moment. SOFT is the
        // pinned parked-art hold — this is the positive control that proves
        // the HARD guard below actually discriminates between the two grades.
        val r = rotation()
        r.view(0, all)
        r.interruptWith(PassengerCard.BUMP, nowMs = 1_000)

        val during = r.view(2_000, all, hold = RotationHold.SOFT)!!

        assertEquals(PassengerCard.BUMP, during.card)
    }

    // --- graded hold: HARD (a finger is on the MAP card's glass) -----------

    @Test
    fun hard_hold_defers_an_interrupt() {
        // Unlike SOFT above, a HARD hold must NOT let a street-crossing bump
        // yank the screen away from an actively-touched map.
        val r = rotation()
        val first = r.view(0, all)!!.card
        r.interruptWith(PassengerCard.BUMP, nowMs = 1_000)

        val during = r.view(2_000, all, hold = RotationHold.HARD)!!

        assertEquals(first, during.card)
        assertEquals(CardReason.ROTATION, during.reason)
    }

    @Test
    fun a_deferred_interrupt_shows_after_release_within_its_window() {
        // Interrupt raised at t=1_000 expires at 1_000 + INTERRUPT = 9_000.
        // Held HARD through 3_000, released at 3_500 — well inside the window.
        val r = rotation()
        r.view(0, all)
        r.interruptWith(PassengerCard.BUMP, nowMs = 1_000)
        r.view(2_000, all, hold = RotationHold.HARD)
        r.view(3_000, all, hold = RotationHold.HARD)

        val afterRelease = r.view(3_500, all)!!

        assertEquals(PassengerCard.BUMP, afterRelease.card)
        assertEquals(CardReason.INTERRUPT, afterRelease.reason)
    }

    @Test
    fun an_interrupt_that_expires_during_a_hard_hold_never_shows() {
        // Same interrupt (expires at 9_000), but the hold now outlasts it —
        // release happens after expiry, so nobody re-raises the stale event.
        val r = rotation()
        val first = r.view(0, all)!!.card
        r.interruptWith(PassengerCard.BUMP, nowMs = 1_000)
        r.view(2_000, all, hold = RotationHold.HARD)
        r.view(9_500, all, hold = RotationHold.HARD)

        val afterRelease = r.view(9_600, all)!!

        assertEquals(first, afterRelease.card)
        assertEquals(CardReason.ROTATION, afterRelease.reason)
    }

    @Test
    fun hard_hold_pauses_dwell_like_soft() {
        // Mirrors holding_keeps_the_current_card_past_its_dwell +
        // releasing_the_hold_resumes_the_remaining_dwell_not_an_instant_flip +
        // the_rotation_advances_normally_once_the_hold_ends, for HARD instead
        // of SOFT: the dwell-pause behaviour of the two grades is identical.
        val r = rotation()
        val first = r.view(0, all)!!.card
        r.view(DWELL / 4, all) // a quarter of the dwell has genuinely elapsed

        assertEquals(first, r.view(DWELL * 5, all, hold = RotationHold.HARD)!!.card)
        assertEquals(first, r.view(DWELL * 20, all, hold = RotationHold.HARD)!!.card)

        // Release: the remaining 3/4 of the original dwell resumes, not an
        // instant flip.
        val justAfterRelease = r.view(DWELL * 20 + 1, all)!!
        assertEquals(first, justAfterRelease.card)

        val later = r.view(DWELL * 20 + DWELL, all)!!
        assertEquals(all[1], later.card)
    }

    // --- currentRotationHold dispatch ---------------------------------------

    @Test
    fun map_card_with_active_touch_dispatches_hard() {
        val hold =
            currentRotationHold(currentCard = PassengerCard.MAP, mapHoldActive = true, parkedArtHoldActive = false)

        assertEquals(RotationHold.HARD, hold)
    }

    @Test
    fun map_card_without_touch_dispatches_none_not_hard() {
        // Negative control for the row above: being on the MAP card alone
        // must not be enough — a mutant that drops the `&& mapHoldActive`
        // guard would return HARD here too.
        val hold =
            currentRotationHold(currentCard = PassengerCard.MAP, mapHoldActive = false, parkedArtHoldActive = true)

        assertEquals(RotationHold.NONE, hold)
    }

    @Test
    fun art_card_when_parked_dispatches_soft() {
        val hold =
            currentRotationHold(currentCard = PassengerCard.ART, mapHoldActive = false, parkedArtHoldActive = true)

        assertEquals(RotationHold.SOFT, hold)
    }

    @Test
    fun other_cards_never_hold_even_if_the_flags_are_set() {
        // A mutant that dropped the `currentCard ==` checks entirely (hoisting
        // hold on the flags alone) would return non-NONE here.
        val hold =
            currentRotationHold(currentCard = PassengerCard.SUN, mapHoldActive = true, parkedArtHoldActive = true)

        assertEquals(RotationHold.NONE, hold)
    }

    @Test
    fun no_current_card_dispatches_none() {
        val hold =
            currentRotationHold(currentCard = null, mapHoldActive = true, parkedArtHoldActive = true)

        assertEquals(RotationHold.NONE, hold)
    }

    // --- Phase 2b: interrupt-layer occupancy cap + guaranteed steady window ---

    /** Runs [card] re-arming every 2 s until the occupancy cap trips, asserting INTERRUPT throughout. */
    private fun CardRotation.rampToCap(card: PassengerCard) {
        var t = 0L
        while (t < CAP) {
            interruptWith(card, nowMs = t)
            assertEquals(CardReason.INTERRUPT, view(t, all)!!.reason)
            t += 2_000
        }
        interruptWith(card, nowMs = CAP)
        assertEquals(CardReason.ROTATION, view(CAP, all)!!.reason) // trips the cap; window opens now
    }

    @Test
    fun a_continuously_rearmed_interrupt_is_capped_then_yields_a_full_steady_window() {
        // Measured on hardware 2026-08-22 (fake ego, parked at the Man): ART's
        // every-tick re-arm kept the ART card on screen forever, starving MAP
        // and SUN of any airtime. It must now cap at interruptMaxMs, yield a
        // full steadyGuaranteeMs of pure rotation despite continued re-arming,
        // then resume — the measured Man-parking case, and the headline.
        val r = rotation()
        var t = 0L
        while (t < CAP) {
            r.interruptWith(PassengerCard.ART, nowMs = t)
            assertEquals(CardReason.INTERRUPT, r.view(t, all)!!.reason)
            t += 1_000
        }

        val windowEnd = CAP + WINDOW
        while (t < windowEnd) {
            // Continuous re-arm, exactly like the measured case — must not
            // pierce the guarantee window.
            r.interruptWith(PassengerCard.ART, nowMs = t)
            assertEquals(CardReason.ROTATION, r.view(t, all)!!.reason)
            t += 1_000
        }

        r.interruptWith(PassengerCard.ART, nowMs = windowEnd)
        val resumed = r.view(windowEnd, all)!!
        assertEquals(PassengerCard.ART, resumed.card)
        assertEquals(CardReason.INTERRUPT, resumed.reason)
    }

    @Test
    fun alternating_interrupt_cards_share_one_occupancy_cap() {
        // ART and WHERE alternate every 2 s. If occupancy reset on a card
        // switch, the pair could collude to hold the screen forever — the cap
        // is on the LAYER, not per card.
        val r = rotation()
        var t = 0L
        var useArt = true
        while (t < CAP) {
            r.interruptWith(if (useArt) PassengerCard.ART else PassengerCard.WHERE, nowMs = t)
            assertEquals(CardReason.INTERRUPT, r.view(t, all)!!.reason)
            useArt = !useArt
            t += 2_000
        }

        // The cap trips at exactly CAP, measured from the FIRST card's
        // display, regardless of how many times the card switched since.
        r.interruptWith(if (useArt) PassengerCard.ART else PassengerCard.WHERE, nowMs = CAP)
        val capped = r.view(CAP, all)!!

        assertEquals(CardReason.ROTATION, capped.reason)
    }

    @Test
    fun a_first_interrupt_after_quiet_shows_immediately() {
        // No interrupt has ever displayed, so no guarantee window exists yet
        // — positive control proving the cap/window machinery starts inert,
        // not pre-armed against the very first event (first-event timeliness
        // is unchanged by Phase 2b).
        val r = rotation()
        r.view(0, all) // steady rotation, nothing armed
        r.interruptWith(PassengerCard.ART, nowMs = 1_000)

        val shown = r.view(1_500, all)!!

        assertEquals(PassengerCard.ART, shown.card)
        assertEquals(CardReason.INTERRUPT, shown.reason)
    }

    @Test
    fun a_one_shot_interrupt_earns_a_window_when_it_expires() {
        // A single WHERE interrupt (no re-arm) displays for its full 8 s and
        // expires naturally — that expiry ALONE must earn the steady
        // guarantee window (not only a cap trip). A fresh arm landing inside
        // that window must defer to its end.
        val r = rotation()
        r.interruptWith(PassengerCard.WHERE, nowMs = 0)
        assertEquals(CardReason.INTERRUPT, r.view(0, all)!!.reason) // occupancy clock starts at 0

        val expired = r.view(INTERRUPT, all)!! // exactly when the interrupt expires
        assertEquals(CardReason.ROTATION, expired.reason)

        // Lands inside the window just granted (ends at INTERRUPT + WINDOW).
        r.interruptWith(PassengerCard.WHERE, nowMs = INTERRUPT + 1_000)
        val deferred = r.view(INTERRUPT + 1_000, all)!!
        assertEquals(CardReason.ROTATION, deferred.reason)
    }

    @Test
    fun an_arm_that_never_displayed_grants_no_window() {
        // Negative control for the window-granting rule: arm an event during
        // an active window, let it go stale WITHOUT ever displaying, and
        // confirm it did not chain a second window — a brand-new fresh arm
        // right at the original window's end must show immediately.
        val r = rotation()
        r.rampToCap(PassengerCard.ART)
        val windowEnd = CAP + WINDOW

        r.interruptWith(PassengerCard.BUMP, nowMs = CAP + 1_000)
        assertEquals(CardReason.ROTATION, r.view(CAP + 1_000, all)!!.reason) // deferred, window still open
        val staleAt = CAP + 1_000 + INTERRUPT + 1
        assertEquals(CardReason.ROTATION, r.view(staleAt, all)!!.reason) // now stale, never shown

        r.interruptWith(PassengerCard.WHERE, nowMs = windowEnd)
        val afterWindow = r.view(windowEnd, all)!!

        assertEquals(PassengerCard.WHERE, afterWindow.card)
        assertEquals(CardReason.INTERRUPT, afterWindow.reason)
    }

    @Test
    fun a_deferred_arm_shows_at_window_end_iff_still_fresh() {
        // Two forks from an identically-opened window: one arm stays fresh
        // past the window's end and must show; a one-shot arm near the
        // window's start goes stale before the window closes and must never
        // show, even though the window itself has ended.
        val freshRotation = rotation()
        freshRotation.rampToCap(PassengerCard.ART)
        val windowEnd = CAP + WINDOW

        freshRotation.interruptWith(PassengerCard.WHERE, nowMs = windowEnd - 2_000)
        assertEquals(CardReason.ROTATION, freshRotation.view(windowEnd - 2_000, all)!!.reason) // deferred

        val stillFresh = freshRotation.view(windowEnd, all)!!
        assertEquals(PassengerCard.WHERE, stillFresh.card)
        assertEquals(CardReason.INTERRUPT, stillFresh.reason)

        val staleRotation = rotation()
        staleRotation.rampToCap(PassengerCard.ART)

        staleRotation.interruptWith(PassengerCard.WHERE, nowMs = CAP + 1_000) // expires at CAP + 9_000
        assertEquals(CardReason.ROTATION, staleRotation.view(CAP + 1_000, all)!!.reason) // deferred

        val stillStale = staleRotation.view(windowEnd, all)!!
        assertEquals(CardReason.ROTATION, stillStale.reason)
    }

    @Test
    fun window_boundary_is_inclusive_release() {
        // The window boundary is `>=` (free on the boundary tick itself);
        // the cap boundary is `<` (releases on the boundary tick itself).
        // Both are pinned exactly, not "roughly around there".
        val windowRotation = rotation()
        windowRotation.rampToCap(PassengerCard.ART)
        val windowEnd = CAP + WINDOW

        windowRotation.interruptWith(PassengerCard.WHERE, nowMs = windowEnd - 1)
        assertEquals(CardReason.ROTATION, windowRotation.view(windowEnd - 1, all)!!.reason)

        windowRotation.interruptWith(PassengerCard.WHERE, nowMs = windowEnd)
        assertEquals(CardReason.INTERRUPT, windowRotation.view(windowEnd, all)!!.reason)

        val capRotation = rotation()
        capRotation.interruptWith(PassengerCard.ART, nowMs = 0)
        assertEquals(CardReason.INTERRUPT, capRotation.view(0, all)!!.reason) // occupancy clock starts at 0

        capRotation.interruptWith(PassengerCard.ART, nowMs = CAP - 1)
        assertEquals(CardReason.INTERRUPT, capRotation.view(CAP - 1, all)!!.reason)

        capRotation.interruptWith(PassengerCard.ART, nowMs = CAP)
        assertEquals(CardReason.ROTATION, capRotation.view(CAP, all)!!.reason)
    }

    @Test
    fun soft_hold_defers_during_a_guarantee_window() {
        // The pinned an_event_still_interrupts_a_held_card above is the
        // positive control: SOFT normally lets a fresh interrupt barge
        // through. But a guarantee window is a property of the LAYER, not of
        // any given hold grade, and must win over SOFT too — otherwise a
        // capped burst would just switch to barging through the hold to
        // recreate the slideshow.
        val r = rotation()
        r.rampToCap(PassengerCard.ART)
        val current = r.view(CAP, all, hold = RotationHold.SOFT)!!.card

        r.interruptWith(PassengerCard.WHERE, nowMs = CAP + 1_000)
        val heldDuringWindow = r.view(CAP + 1_000, all, hold = RotationHold.SOFT)!!

        assertEquals(current, heldDuringWindow.card)
        assertEquals(CardReason.ROTATION, heldDuringWindow.reason)
    }

    @Test
    fun hard_hold_and_cap_compose() {
        // A fresh interrupt arrives just before a guarantee window closes,
        // held HARD (finger on the map) straight through the window's end and
        // a bit past. HARD's defer and the window's defer are two
        // independent gates on the same interrupt — release must show it the
        // moment BOTH have cleared, without either gate corrupting the
        // other's bookkeeping.
        val r = rotation()
        r.rampToCap(PassengerCard.ART)
        val windowEnd = CAP + WINDOW

        r.interruptWith(PassengerCard.WHERE, nowMs = windowEnd - 2_000) // expires at windowEnd + 6_000
        r.view(windowEnd - 2_000, all, hold = RotationHold.HARD)
        r.view(windowEnd + 1_000, all, hold = RotationHold.HARD) // window has lapsed; still HARD-held

        val released = r.view(windowEnd + 1_500, all)!!

        assertEquals(PassengerCard.WHERE, released.card)
        assertEquals(CardReason.INTERRUPT, released.reason)
    }

    @Test
    fun the_rotation_lands_where_the_clock_says_after_a_capped_burst() {
        // The dwell clock must not accrue time while the interrupt layer
        // holds the screen (unchanged), but must resume normally the instant
        // the cap/window suppress it back to steady rotation — a capped burst
        // freezes the clock only for the sliver actually spent as INTERRUPT,
        // not for the whole burst+window span. Extends the existing
        // long-interrupt catch-up coverage
        // (a_long_interrupt_lands_the_rotation_where_it_would_have_been) into
        // the capped case.
        val r = rotation()
        r.rampToCap(PassengerCard.ART)
        val firstSteady = r.view(CAP, all)!! // dwell clock starts fresh here
        assertEquals(CardReason.ROTATION, firstSteady.reason)
        assertEquals(all[0], firstSteady.card)

        val afterOneDwell = r.view(CAP + DWELL, all)!!
        val afterThreeDwells = r.view(CAP + DWELL * 3, all)!!

        assertEquals(all[1], afterOneDwell.card)
        assertEquals(all[3], afterThreeDwells.card)
    }

    private companion object {
        const val DWELL = 25_000L
        const val INTERRUPT = 8_000L

        /** Test-local mirror of CardRotation.INTERRUPT_MAX_CONTINUOUS_MS. */
        const val CAP = 12_000L

        /** Test-local mirror of CardRotation.STEADY_GUARANTEE_MS. */
        const val WINDOW = 25_000L
    }
}
