package org.pureagave.zodiac.control.core.passenger

/**
 * The cards a passenger display cycles through. Ordered as the rotation runs.
 *
 * Everything here is driven by data already broadcasting on the fleet bus —
 * the beacon's NMEA channels and the Jetson's threat frames — so a passenger
 * tablet is a pure consumer: no new hardware, no new plumbing, and any number
 * of them can listen to the same multicast without the senders knowing.
 */
enum class PassengerCard {
    /** Big playa address: clock + street, updating as the vehicle drives. */
    WHERE,

    /**
     * Interactive, track-up, auto-following live BRC map centred on the
     * vehicle — pan and pinch-zoom, no rotate, recenter to return. Right
     * after [WHERE] by design: address, then the map of it, the two "where
     * are we" answers running back-to-back. See
     * `design/PASSENGER-UI-split-spec.md` Phase 2.
     */
    MAP,

    /** Oscilloscope driven by the beacon mic's `$ZAUD` level. */
    AUDIO,

    /** Count + sweep of the people the Jetson can see around the vehicle. */
    SOULS,

    /** `$ZSHK` impact peaks as a playa-washboard turbulence gauge. */
    BUMP,

    /** `$ZODO` trip + lifetime distance for this burn. */
    TRIP,

    /**
     * The vehicle's raw feed, for the rider who asks what all the numbers are.
     * Same source-status line + scrolling ticker the cockpit TELEMETRY box
     * shows (`ui/concepts/TelemetryBox.kt`), reused verbatim with the
     * passenger colour policy — see `ui/passenger/PassengerTelemetryCard.kt`.
     * Right after [TRIP] by design: the data-flavoured cards (BUMP, TRIP,
     * TELEMETRY) cluster together before the ambient SUN/ART pair closes the
     * loop.
     */
    TELEMETRY,

    /** Countdown to the next sunrise or sunset. */
    SUN,

    /** Notable art ahead, once the BM API releases locations. */
    ART,
}

/**
 * Why the display is showing what it's showing. Kept explicit so the screen
 * can style an interrupt differently from the steady rotation — a card that
 * barged in because something *just happened* should not look identical to one
 * that came up on a timer.
 */
enum class CardReason { ROTATION, INTERRUPT }

/** What the passenger screen should draw right now. */
data class PassengerView(
    val card: PassengerCard,
    val reason: CardReason,
)
