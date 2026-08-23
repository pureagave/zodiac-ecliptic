package org.pureagave.zodiac.control.core.telemetry

import java.util.Locale
import kotlin.math.log10

/*
 * Pure model for the MAP AMBIENT+SHOCK tile (COCKPIT-TELEMETRY-REDESIGN Phase
 * C), which replaces the old prop speed gauge with live `$ZENV` lux and
 * `$ZSHK` shock-peak readouts. formatLuxValue reuses formatLux (Phase A's
 * ticker formatter) rather than restating the lux decade-formatting rule —
 * one definition, both callers.
 *
 * Every function here follows the same honesty rule: an absent sensor reading
 * (null) maps to null/"—", NEVER a fabricated zero. A zero-fill bar would read
 * as "confirmed dark" or "confirmed no shock", which a dead beacon cannot
 * claim.
 */

/** Lux span the log bar covers — a chosen UX span (moonless playa to noon sun), not a datasheet number. */
private const val LUX_BAR_MIN: Double = 0.1
private const val LUX_BAR_MAX: Double = 100_000.0

/**
 * Shock span the linear bar covers, in g — a chosen UX span bracketing the
 * beacon's observed event range (0.6 g "playa", 1.5 g "hold on" —
 * `PassengerScreen.kt`'s `bumpVerdict`), not a datasheet number.
 */
private const val SHOCK_BAR_MAX_G: Double = 2.0

private const val DASH = "—"

/**
 * 0..1 fill for a log-scale lux bar spanning [LUX_BAR_MIN]..[LUX_BAR_MAX] lx.
 * Log rather than linear because the sensor spans five decades (moonless
 * playa ≈ 0.1 lx, noon ≈ 100,000 lx); a linear bar would read as a binary
 * day/night flag instead of a gauge. `null` lux (no beacon) ⇒ `null`: draw no
 * bar at all, never a fake empty (0%) or full reading.
 */
fun luxBarFraction(lux: Double?): Float? {
    val value = lux ?: return null
    val clamped = value.coerceIn(LUX_BAR_MIN, LUX_BAR_MAX)
    val frac = (log10(clamped) - log10(LUX_BAR_MIN)) / (log10(LUX_BAR_MAX) - log10(LUX_BAR_MIN))
    return frac.toFloat().coerceIn(0f, 1f)
}

/**
 * 0..1 fill for a 0..[SHOCK_BAR_MAX_G] g linear shock bar. Values above the
 * span clamp to full rather than being rejected — a jolt harder than the UX
 * span is still a real jolt, just pinned. `null` (no event yet this session)
 * ⇒ `null`, same honesty rule as [luxBarFraction].
 */
fun shockBarFraction(g: Double?): Float? {
    val value = g ?: return null
    return (value / SHOCK_BAR_MAX_G).toFloat().coerceIn(0f, 1f)
}

/** `formatLux` (Phase A) plus the tile's dash-on-absence rule. */
fun formatLuxValue(lux: Double?): String = lux?.let(::formatLux) ?: DASH

/** `"1.2G"`, `Locale.US`-pinned; `"—"` when there has been no shock event this session. */
fun formatShockValue(g: Double?): String = g?.let { "%.1fG".format(Locale.US, it) } ?: DASH
