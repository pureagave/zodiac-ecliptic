package org.pureagave.zodiac.control.core.ops

import org.pureagave.zodiac.control.BuildConfig
import org.pureagave.zodiac.control.core.geo.LatLon

/**
 * Zodiac's home camp. The "return to camp" / "shelter bearing" features point
 * here from the live GPS fix.
 *
 * **Configured per build, never committed.** The real camp is set in the
 * gitignored `local.properties` (`zodiac.home.lat` / `zodiac.home.lon`) and
 * reaches this object through `BuildConfig`; without those keys the build
 * uses [DEFAULT] — a neutral city address, not anyone's camp. Every tablet
 * and the beacon read the same root-build value, so a fleet flashed from one
 * checkout always agrees on HOME. See `build.gradle.kts`.
 *
 * Camp placements move every year, so the configured value wants re-checking
 * against the BM API geocode once placements are released.
 */
object Home {
    /**
     * **7:30 & E on the 2026 grid** — the neutral default: the E ring radius
     * (1237 m, [StreetRingRadiiM]) on the 7:30 radial (270° true: axis 45° +
     * 7.5 h × 30°), projected about the 2026 Man (equirectangular, matching
     * `PlayaProjection`). Pinned against the bundled GIS by `NavTargetTest`.
     */
    val DEFAULT: LatLon = LatLon(lon = -119.2225761, lat = 40.7832474)

    /** The configured home camp (or [DEFAULT]). */
    val LOCATION: LatLon = LatLon(lon = BuildConfig.HOME_LON, lat = BuildConfig.HOME_LAT)
}
