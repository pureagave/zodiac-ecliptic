package org.pureagave.zodiac.control.core.geo

/**
 * Greedy label declutter. Callers offer labels in priority order, and a label
 * is placed only if its box (grown by [gapPx] on every side) overlaps no box
 * already placed since the last [reset], so the first claimant of a spot keeps
 * it and lower-priority text drops out instead of printing over it. A box
 * entirely outside the viewport is rejected without claiming space.
 *
 * Boxes live in one growable [FloatArray] that survives [reset], so the draw
 * path allocates nothing once warmed up. Not thread-safe; one per draw loop.
 */
class LabelPlacer(
    private val gapPx: Float = DEFAULT_GAP_PX,
) {
    private var boxes = FloatArray(INITIAL_CAPACITY * 4)
    private var count = 0
    private var viewW = Float.POSITIVE_INFINITY
    private var viewH = Float.POSITIVE_INFINITY

    /** Forget every placed box and set the viewport for the next pass. */
    fun reset(
        viewportWidth: Float,
        viewportHeight: Float,
    ) {
        count = 0
        viewW = viewportWidth
        viewH = viewportHeight
    }

    /** Claim the box if it is on screen and clear of every placed box. */
    fun tryPlace(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ): Boolean {
        if (!onScreen(left, top, right, bottom)) return false
        val l = left - gapPx
        val t = top - gapPx
        val r = right + gapPx
        val b = bottom + gapPx
        for (i in 0 until count) if (overlaps(i * 4, l, t, r, b)) return false
        if ((count + 1) * 4 > boxes.size) boxes = boxes.copyOf(boxes.size * 2)
        val o = count * 4
        boxes[o] = l
        boxes[o + 1] = t
        boxes[o + 2] = r
        boxes[o + 3] = b
        count++
        return true
    }

    private fun onScreen(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ): Boolean = right >= 0f && bottom >= 0f && left <= viewW && top <= viewH

    private fun overlaps(
        o: Int,
        l: Float,
        t: Float,
        r: Float,
        b: Float,
    ): Boolean = l < boxes[o + 2] && r > boxes[o] && t < boxes[o + 3] && b > boxes[o + 1]

    /** Boxes placed since the last [reset]. */
    val placedCount: Int get() = count

    private companion object {
        const val INITIAL_CAPACITY = 64
        const val DEFAULT_GAP_PX = 2f
    }
}
