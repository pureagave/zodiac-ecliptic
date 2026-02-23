package org.pureagave.zodiac.control

import org.junit.Assert.assertEquals
import org.junit.Test

class ExampleUnitTest {
    @Test
    fun clampHeading() {
        val heading = 420f.coerceIn(0f, 359f)
        assertEquals(359f, heading)
    }
}
