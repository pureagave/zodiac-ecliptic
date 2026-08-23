package org.pureagave.zodiac.control.core.model

/**
 * The charger-failure threshold for CELL A (COCKPIT-TELEMETRY-REDESIGN Phase
 * D): at or below this percent, the tile renders as a fault, not a status
 * nicety. Mirrors the beacon's own `BEACON_BATTERY_LOW_PCT = 20`
 * (`OpsReadout.kt`) — one project-wide convention for "battery low is a
 * fault". A plugged-in display on permanent vehicle power sits near 100%;
 * anything that has drifted down to 20% has been off charge for a long time.
 */
const val DEVICE_BATTERY_LOW_PCT: Int = 20

/**
 * Percent from `ACTION_BATTERY_CHANGED`'s `EXTRA_LEVEL`/`EXTRA_SCALE`, or
 * `null` when the platform reports garbage (`level < 0` or `scale <= 0` —
 * seen on emulators and some vendor builds). `null` means UNKNOWN and must
 * render "—", **never** a fake number: a fabricated 0% would read as "dead
 * battery" for a display whose charger is actually fine.
 *
 * Integer arithmetic (`level * 100 / scale`), then coerced into 0..100 so a
 * vendor reporting `level` slightly above `scale` can't escape the gauge's
 * axis.
 */
fun batteryPercent(
    level: Int,
    scale: Int,
): Int? {
    if (level < 0 || scale <= 0) return null
    return (level * 100 / scale).coerceIn(0, 100)
}
