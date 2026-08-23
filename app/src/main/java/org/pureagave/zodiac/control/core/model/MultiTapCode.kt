package org.pureagave.zodiac.control.core.model

/**
 * Recognises N quick taps on one hot zone within a rolling window — the
 * TELEMETRY box header's hidden trigger for the dev/bench control panel.
 *
 * [org.pureagave.zodiac.control.core.kiosk.KioskExitCode]'s pure-clock-injected
 * shape, reduced to a single zone: that recogniser encodes a two-zone sequence
 * tied to the tablet's provisioned kiosk-exit code and must stay exactly as
 * already-provisioned tablets expect, so this is a new, smaller class rather
 * than a reuse of it.
 *
 * A tap outside [windowMs] of the previous one restarts the count *at* 1 (the
 * tap itself still counts), mirroring `KioskExitCode`'s "a fumbled retry still
 * completes" behaviour rather than discarding it.
 */
class MultiTapCode(
    private val requiredTaps: Int = DEFAULT_REQUIRED_TAPS,
    private val windowMs: Long = DEFAULT_WINDOW_MS,
) {
    init {
        require(requiredTaps > 0) { "requiredTaps must be positive" }
    }

    private var count = 0
    private var lastTapMs = Long.MIN_VALUE

    /** Feed a tap at [nowMs]; returns true exactly on the tap that reaches [requiredTaps]. */
    fun tap(nowMs: Long): Boolean {
        val stale = nowMs - lastTapMs > windowMs
        lastTapMs = nowMs
        count = if (stale) 1 else count + 1
        if (count >= requiredTaps) {
            count = 0
            return true
        }
        return false
    }

    companion object {
        /** The TELEMETRY box header trigger: 5 taps, as decided 2026-08-22. */
        const val DEFAULT_REQUIRED_TAPS: Int = 5

        /** Each tap must land within this of the previous, else the count restarts. */
        const val DEFAULT_WINDOW_MS: Long = 2_000L
    }
}
