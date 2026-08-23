package org.pureagave.zodiac.control.core.telemetry

/**
 * One 10 s speed sample: [bucket] is the absolute bucket index (`timeMs /
 * [SpeedTrail.BUCKET_MS]`), [kph] the clamped speed recorded for it.
 */
data class SpeedSample(val bucket: Long, val kph: Float)

/**
 * A pure, session-scoped ring of the last 30 minutes of
 * [org.pureagave.zodiac.control.ui.state.CockpitUiState.effectiveSpeedKph],
 * time-bucketed at [BUCKET_MS] (10 s) so the MAP throttle-trace tile can draw
 * a real trend line instead of the hard-coded literal array it replaces
 * (COCKPIT-TELEMETRY-REDESIGN Phase B).
 *
 * **No interpolation, no coasting — DECIDED.** A bucket the ViewModel never
 * sampled (the process asleep, the app restarted, a concept switch that
 * paused sampling — none of these apply today, but the model doesn't assume
 * they never will) stays a gap in [window]; the renderer breaks the polyline
 * there rather than drawing a fabricated straight line across missing data.
 *
 * Session-scoped, not persisted: an app restart starts from [EMPTY], and the
 * leading slots fill in left-to-right over the next 30 minutes. That is
 * honest and cheap, and is why there is no serialization here.
 *
 * The axis bounds mirror
 * [org.pureagave.zodiac.control.ui.state.CockpitUiState.MIN_SPEED_KPH] /
 * `MAX_SPEED_KPH` by value, duplicated rather than imported: `core/` does not
 * depend upward on `ui/state` (see CLAUDE.md's layering — "core/ — all pure
 * logic, no Android"), so the two constants are pinned here and must be kept
 * in step by hand. [SpeedTrailTest] pins the mirrored values directly.
 */
data class SpeedTrail(
    // Ascending by bucket; not required to be contiguous (missed ticks are
    // simply absent entries, not zero-filled ones).
    val samples: List<SpeedSample> = emptyList(),
) {
    /**
     * Records [kph] (clamped into the fixed 0–160 axis) into the bucket
     * `nowMs / [BUCKET_MS]`. A second call landing in the same bucket
     * overwrites the first — last write per bucket wins, mirroring "what does
     * the cockpit currently claim the speed is" rather than averaging within
     * a bucket. Anything older than the [WINDOW_BUCKETS]-bucket window
     * (relative to *this* [nowMs]) is evicted, so the list never grows
     * unbounded across a long session.
     */
    fun record(
        nowMs: Long,
        kph: Double,
    ): SpeedTrail {
        val bucket = nowMs / BUCKET_MS
        val clamped = kph.toFloat().coerceIn(AXIS_MIN_KPH, AXIS_MAX_KPH)
        val oldestKeptBucket = bucket - WINDOW_BUCKETS + 1
        val survivors =
            samples
                .filterNot { it.bucket == bucket }
                .filter { it.bucket >= oldestKeptBucket }
        return SpeedTrail((survivors + SpeedSample(bucket, clamped)).sortedBy { it.bucket })
    }

    /**
     * Lays the current samples into exactly [WINDOW_BUCKETS] fixed slots,
     * oldest first, ending at the bucket containing [nowMs]. A slot with no
     * matching sample is `null` — a gap in the data is a gap in the line, by
     * design (see the class doc). Always exactly [WINDOW_BUCKETS] long, empty
     * or not, so the renderer never has to special-case a variable-length
     * list.
     */
    fun window(nowMs: Long): List<Float?> {
        val newestBucket = nowMs / BUCKET_MS
        val oldestBucket = newestBucket - WINDOW_BUCKETS + 1
        val byBucket = samples.associate { it.bucket to it.kph }
        return (oldestBucket..newestBucket).map { byBucket[it] }
    }

    companion object {
        /** Sample cadence — see the spec's measured justification (280dp column, Fire density). */
        const val BUCKET_MS: Long = 10_000L

        /** 180 buckets * 10 s = 30 minutes. */
        const val WINDOW_BUCKETS: Int = 180

        val EMPTY = SpeedTrail()

        // Mirrors CockpitUiState.MIN_SPEED_KPH / MAX_SPEED_KPH — see the class doc.
        private const val AXIS_MIN_KPH: Float = 0f
        private const val AXIS_MAX_KPH: Float = 160f
    }
}
