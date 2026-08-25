package org.pureagave.zodiac.control.core.passenger

/**
 * How strongly [CardRotation.view] should hold the current card, replacing a
 * plain boolean because the passenger MAP card needs a *stronger* hold than
 * the parked-art case: both pause the dwell clock, but only [HARD] also
 * defers interrupts, because a finger is physically on the glass.
 */
enum class RotationHold {
    /** No hold — normal rotation. */
    NONE,

    /** Pause the dwell clock; interrupts still barge in (the parked-art hold, pinned behaviour). */
    SOFT,

    /** Pause the dwell clock AND defer interrupts without clearing them — a finger is on the glass. */
    HARD,
}

/**
 * Decides which passenger card is on screen: a timed rotation that events can
 * barge into.
 *
 * Nobody touches a passenger display, so it has to be interesting without
 * input — hence the rotation. But a rotation alone makes the screen feel
 * disconnected from the ride, so the things that *just happened* (crossing a
 * street, hitting a bad patch of playa) interrupt it. The interrupt then
 * expires and the rotation resumes from where it was, rather than restarting:
 * a bump shouldn't cost you the card you were reading.
 *
 * Pure and clock-injected, because everything interesting here is about time
 * and precedence — both miserable to verify by watching a tablet.
 *
 * @param available cards that currently have data worth showing. A card with
 *   nothing behind it (no art released yet, no beacon for the odometer) is
 *   excluded by the caller rather than shown empty — a passenger display that
 *   cycles through blank cards reads as broken.
 */
class CardRotation(
    private val dwellMs: Long = DEFAULT_DWELL_MS,
    private val interruptMs: Long = DEFAULT_INTERRUPT_MS,
    private val interruptMaxMs: Long = INTERRUPT_MAX_CONTINUOUS_MS,
    private val steadyGuaranteeMs: Long = STEADY_GUARANTEE_MS,
) {
    private var index = 0
    private var lastAdvanceMs = Long.MIN_VALUE
    private var interrupt: PassengerCard? = null
    private var interruptUntilMs = 0L
    private var heldElapsedMs = 0L

    // Phase 2b — interrupt-layer occupancy cap + guaranteed steady window.
    // `interruptVisible` is whatever the *previous* call to [view] returned:
    // true iff it was CardReason.INTERRUPT. The other two fields are only
    // meaningful relative to a true/false edge of that flag — see
    // [noteInterruptTransition].
    private var interruptVisible = false
    private var interruptShownSinceMs = 0L
    private var steadyUntilMs = 0L

    /**
     * Raise [card] over the rotation until [interruptMs] has passed. A second
     * interrupt of the same card re-arms the timer (a run of bumps holds the
     * gauge up); a different one takes over immediately, because the newest
     * event is the one the passenger just felt.
     *
     * A re-arm is not a guarantee of display: [view] may still defer it — see
     * [interruptMaxMs] and [steadyGuaranteeMs] — without clearing it, so a
     * still-fresh event shows the moment it is allowed to.
     */
    fun interruptWith(
        card: PassengerCard,
        nowMs: Long,
    ) {
        interrupt = card
        interruptUntilMs = nowMs + interruptMs
    }

    /**
     * What to show at [nowMs], given the cards that currently have data.
     * Returns null only when nothing at all is available.
     *
     * @param hold keep the current card up instead of advancing, graded by how
     *   strongly. [RotationHold.NONE] rotates normally. [RotationHold.SOFT]
     *   pauses the dwell clock — used when the vehicle has stopped next to
     *   something worth reading: parked is exactly when passengers have time
     *   for a long description, and rotating it away mid-sentence is the most
     *   annoying thing this display could do — but a fresh interrupt still
     *   barges in, because a bump the passenger just felt deserves its moment
     *   even over a held ART card. [RotationHold.HARD] pauses the dwell clock
     *   *and* defers interrupts without clearing them: a finger is on the
     *   glass (the interactive MAP card), and yanking the screen away to show
     *   an unrelated gauge mid-gesture would be worse than the interrupt
     *   arriving a few seconds late. Either grade's dwell pause resumes the
     *   current card's remaining dwell on release rather than instantly
     *   flipping — otherwise pulling away/releasing would snatch the card at
     *   the moment someone looked back up.
     *
     *   A finger on the glass is not the only thing that can defer an
     *   interrupt: the interrupt *layer* itself is bounded (see
     *   [interruptMaxMs], [steadyGuaranteeMs]) so that a continuously
     *   re-armed event — an approaching art piece, a run of street crossings
     *   — cannot monopolize the screen and starve the steady rotation
     *   (MAP, SUN, …) of airtime. That bound is orthogonal to [hold]: even a
     *   [RotationHold.SOFT] barge-in is deferred while a guarantee window is
     *   open.
     */
    fun view(
        nowMs: Long,
        available: List<PassengerCard>,
        hold: RotationHold = RotationHold.NONE,
    ): PassengerView? {
        if (available.isEmpty()) {
            interrupt = null
            noteInterruptTransition(shown = false, nowMs = nowMs)
            return null
        }

        val pending = interrupt
        val fresh = pending != null && nowMs < interruptUntilMs && pending in available
        val showInterrupt = fresh && interruptAllowed(hold, nowMs)
        noteInterruptTransition(shown = showInterrupt, nowMs = nowMs)

        if (showInterrupt) {
            return PassengerView(pending!!, CardReason.INTERRUPT)
        }
        if (hold != RotationHold.HARD && !fresh) {
            // Genuinely stale (past interruptUntilMs) or no longer in
            // available — drop it, same as before Phase 2b: the event still
            // happened, but we've nothing to draw, or nobody re-raises a
            // stale one. A *fresh* interrupt blocked only by HARD, the cap,
            // or the guarantee window is left armed with its original expiry
            // — "defer without clearing" — so it can still show later.
            interrupt = null
        }

        if (lastAdvanceMs == Long.MIN_VALUE) lastAdvanceMs = nowMs
        if (hold != RotationHold.NONE) {
            // Slide the dwell window forward with the clock so no time accrues
            // against the held card. Same treatment for SOFT and HARD — the
            // two grades differ only in interrupt handling above.
            lastAdvanceMs = nowMs - heldElapsedMs
        } else {
            heldElapsedMs = (nowMs - lastAdvanceMs).coerceIn(0L, dwellMs)
            // A loop, not an if: a long interrupt can span several dwell
            // periods, and the rotation should land where it would have been
            // rather than creep forward one card per query.
            while (nowMs - lastAdvanceMs >= dwellMs) {
                lastAdvanceMs += dwellMs
                index++
            }
        }
        // Reduce in place rather than letting `index` grow forever. Same
        // reasoning as the fake telemetry tick: a display that runs for months
        // shouldn't have an overflow horizon at all, and a bounded index means
        // a plain `%` is provably safe here instead of needing a floorMod whose
        // negative branch nothing could ever reach.
        index %= available.size
        return PassengerView(available[index], CardReason.ROTATION)
    }

    /**
     * Manually advance to the next steady card — the passenger tapped the
     * screen. One tap, one card, regardless of what the rotation was doing:
     *
     *  - [index] steps forward once; the next [view] reduces it modulo the
     *    available list, so wrapping past the end is free and a tap can never
     *    index out of bounds even if the list shrinks underneath it.
     *  - [lastAdvanceMs] restarts at [nowMs], so the tapped-to card gets a
     *    full dwell rather than the remainder of the old card's.
     *  - [heldElapsedMs] resets, so a hold that begins on the new card
     *    (tapping to ART while parked) treats it as freshly arrived —
     *    stale elapsed time would otherwise flip it moments after release.
     *  - [interrupt] clears: a tap while BUMP/ART/WHERE is up means "give me
     *    the next actual card", and a tap that visibly does nothing reads
     *    as broken.
     *
     * Deliberately does NOT touch the interrupt-layer bookkeeping
     * ([interruptVisible] / [interruptShownSinceMs] / [steadyUntilMs]). The
     * next [view] sees the layer hidden and [noteInterruptTransition] closes
     * it, opening the normal steady-guarantee window — and that window is
     * what makes a tap-dismissal stick: ART re-arms every tick while a piece
     * is ahead, and without the window it would barge straight back over the
     * card the passenger just asked for. Clearing the flags here would
     * suppress that shown->hidden edge, grant no window, and undo the tap one
     * tick later.
     *
     * @param nowMs the rotation's clock (the screen's tick clock, not wall
     *   time) — the same timebase every other entry point uses.
     */
    fun advance(nowMs: Long) {
        index++
        lastAdvanceMs = nowMs
        heldElapsedMs = 0L
        interrupt = null
    }

    /**
     * The two layer-level gates, independent of [hold]'s own interrupt guard
     * (the caller has already established the interrupt is [fresh]):
     * - the guarantee window from a previous shown→hidden transition must
     *   have elapsed ([steadyUntilMs], `>=` — the boundary tick is free), and
     * - the layer must not already have been visible continuously for
     *   [interruptMaxMs] (`<` — at exactly the cap the layer releases).
     *
     * Split out of [view] to keep that method's branching about *dispatch*
     * (show / defer / rotate) rather than growing a compound boolean inline.
     */
    private fun interruptAllowed(
        hold: RotationHold,
        nowMs: Long,
    ): Boolean {
        if (hold == RotationHold.HARD) return false
        val windowOver = nowMs >= steadyUntilMs
        val underCap = !interruptVisible || nowMs - interruptShownSinceMs < interruptMaxMs
        return windowOver && underCap
    }

    /**
     * One uniform bookkeeping rule for the interrupt layer's occupancy, run on
     * every call to [view] regardless of *why* [shown] is what it is — a HARD
     * hold, the cap, the window, genuine expiry, or an empty `available` list
     * are all alike here:
     * - not shown → shown: the occupancy clock starts now.
     * - shown → not shown: a fresh guarantee window opens from now.
     * - no edge: no bookkeeping. In particular, an interrupt that was armed
     *   and then went stale *without ever displaying* (deferred the whole
     *   time by a hold, the cap, or a window) never triggers this edge, so it
     *   grants no window of its own — only actual occupancy buys the
     *   rotation its recovery time.
     */
    private fun noteInterruptTransition(
        shown: Boolean,
        nowMs: Long,
    ) {
        if (shown && !interruptVisible) {
            interruptVisible = true
            interruptShownSinceMs = nowMs
        } else if (!shown && interruptVisible) {
            interruptVisible = false
            steadyUntilMs = nowMs + steadyGuaranteeMs
        }
    }

    companion object {
        /** Long enough to read a card, short enough that the screen never feels static. */
        const val DEFAULT_DWELL_MS: Long = 25_000L

        /** How long an event holds the screen before the rotation resumes. */
        const val DEFAULT_INTERRUPT_MS: Long = 8_000L

        /**
         * How long the interrupt layer — ART's approach cone, WHERE's street
         * crossings, any future interrupt card — may hold the screen
         * continuously, across re-arms *and* across a switch to a different
         * interrupt card, before the steady rotation is guaranteed a turn.
         * 1.5x [DEFAULT_INTERRUPT_MS]: a run of bumps still holds the gauge up
         * meaningfully longer than one display, but not forever. Measured on
         * hardware 2026-08-22: without this bound, ART's every-tick re-arm
         * near the Man held the screen indefinitely (see
         * `design/PASSENGER-UI-split-spec.md` Phase 2b).
         */
        const val INTERRUPT_MAX_CONTINUOUS_MS: Long = 12_000L

        /**
         * How long the steady rotation is guaranteed to run, interrupt-free,
         * immediately after the interrupt layer stops being shown for any
         * reason (natural expiry, the occupancy cap tripping, or the
         * `available` list going empty). One full card's dwell.
         */
        const val STEADY_GUARANTEE_MS: Long = DEFAULT_DWELL_MS
    }
}

/**
 * Which [RotationHold] grade applies right now, given the card currently on
 * screen and the two independent hold conditions the passenger screen's
 * ticker tracks: a touched MAP card ([mapHoldActive]) and a parked-next-to-art
 * ART card ([parkedArtHoldActive]). The two are mutually exclusive by
 * construction — each only matters for its own card — so this is a pure
 * combination of already-decided signals, not a new policy of its own.
 *
 * Pulled out of the screen's ticker (rather than a `when` inlined there) so
 * the composable's own cyclomatic complexity stays about timing and
 * dispatch, not this decision — and so the combination is unit-testable on
 * its own.
 */
fun currentRotationHold(
    currentCard: PassengerCard?,
    mapHoldActive: Boolean,
    parkedArtHoldActive: Boolean,
): RotationHold =
    when {
        currentCard == PassengerCard.MAP && mapHoldActive -> RotationHold.HARD
        currentCard == PassengerCard.ART && parkedArtHoldActive -> RotationHold.SOFT
        else -> RotationHold.NONE
    }
