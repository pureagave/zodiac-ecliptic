package org.pureagave.zodiac.beacon

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * `Nmea.gga`/`Nmea.rmc` (the GPS-simulation-mode sentence builders — see
 * `GpsSim.kt`) mirror `NmeaTest`'s house style: hand-computed literals, an
 * XOR checksum re-implemented independently in the test (never by calling
 * [Nmea.checksum]), and a `Locale.GERMANY` control.
 */
class NmeaSimSentenceTest {
    private val original: Locale = Locale.getDefault()

    @After
    fun restore() {
        Locale.setDefault(original)
    }

    @Test
    fun gga_and_rmc_pin_the_man_preset_at_a_fixed_epoch() {
        // Hand-computed literals (worked in the spec, §2): 40.783247448000054°
        // -> 40 deg + 0.783247448000054*60 = 46.99484688' -> rounds to
        // "4046.9948,N"; -119.20788409599999 -> 119 deg +
        // 0.20788409599999*60 = 12.47304576' -> rounds to "11912.4730,W".
        // epoch 0 = 1970-01-01T00:00:00Z by definition of the Unix epoch, so
        // the hhmmss.ss/ddMMyy fields are hand-verifiable with no dependency
        // on the implementation's own date math: "000000.00" / "010170".
        val gga = Nmea.gga(SimPreset.MAN.lat, SimPreset.MAN.lon, epochMillis = 0L)
        val rmc = Nmea.rmc(SimPreset.MAN.lat, SimPreset.MAN.lon, speedKph = 0.0, headingDeg = 0.0, epochMillis = 0L)

        val expectedGgaBody = "GPGGA,000000.00,4046.9948,N,11912.4730,W,8,12,0.8,1191.0,M,0.0,M,,"
        val expectedRmcBody = "GPRMC,000000.00,A,4046.9948,N,11912.4730,W,0.0,0.0,010170,,,A"

        assertEquals("\$$expectedGgaBody*${independentXor(expectedGgaBody)}\r\n", gga)
        assertEquals("\$$expectedRmcBody*${independentXor(expectedRmcBody)}\r\n", rmc)
    }

    @Test
    fun gga_has_at_least_10_fields_with_simulation_quality_sats_and_hdop() {
        // Mutation target: any of the hard-coded SIM_FIX_QUALITY/SIM_SATELLITES/
        // SIM_HDOP values swapped or dropped.
        val fields = Nmea.gga(SimPreset.MAN.lat, SimPreset.MAN.lon, 0L).fields()
        assertTrue(fields.size >= 10)
        assertEquals("8", fields[6])
        assertEquals("12", fields[7])
        assertEquals("0.8", fields[8])
    }

    @Test
    fun rmc_has_at_least_9_fields_with_status_a_knots_and_course() {
        val fields = Nmea.rmc(SimPreset.MAN.lat, SimPreset.MAN.lon, speedKph = 8.0, headingDeg = 47.0, epochMillis = 0L).fields()
        assertTrue(fields.size >= 9)
        assertEquals("A", fields[2])
        assertEquals("4.3", fields[7]) // 8.0 / 1.852
        assertEquals("47.0", fields[8])
    }

    @Test
    fun southern_and_eastern_hemispheres_use_the_correct_letters() {
        val fields = Nmea.gga(-33.9, 151.2, 0L).fields()
        assertEquals("S", fields[3])
        assertEquals("E", fields[5])
    }

    @Test
    fun northern_and_western_hemispheres_use_the_correct_letters_as_the_positive_control() {
        val fields = Nmea.gga(33.9, -151.2, 0L).fields()
        assertEquals("N", fields[3])
        assertEquals("W", fields[5])
    }

    @Test
    fun knots_conversion_spot_check() {
        val fields = Nmea.rmc(0.0, 0.0, speedKph = 8.0, headingDeg = 0.0, epochMillis = 0L).fields()
        assertEquals("4.3", fields[7])
    }

    @Test
    fun gga_and_rmc_keep_their_field_count_even_in_a_comma_locale() {
        // A comma-decimal format anywhere (e.g. a %.1f without Locale.US) would
        // split a numeric field into two, inflating the field count -- this is
        // the field-count analogue of NmeaTest's startsWith(...) locale guard,
        // sized for sentences with many numeric fields instead of one.
        Locale.setDefault(Locale.GERMANY)
        val ggaFields = Nmea.gga(SimPreset.MAN.lat, SimPreset.MAN.lon, 0L).fields()
        val rmcFields = Nmea.rmc(SimPreset.MAN.lat, SimPreset.MAN.lon, 8.0, 47.0, 0L).fields()
        assertEquals(15, ggaFields.size)
        assertEquals(13, rmcFields.size)
    }

    @Test
    fun sim_tick_sentences_gates_the_fix_on_the_4_tick_divisor() {
        // Mutation target: the `% SIM_FIX_EVERY_TICKS` divisor changed (e.g. to
        // always-true or always-false).
        val sim = GpsSimulator(SimSpec(SimPreset.MAN.lat, SimPreset.MAN.lon, 90.0, 5.0, "MAN"), startedAtMs = 0L)
        assertEquals(3, simTickSentences(sim, tick = 0L, epochMs = 0L).size)
        for (tick in 1..3) {
            assertEquals(1, simTickSentences(sim, tick.toLong(), epochMs = 1_000L).size)
        }
        assertEquals(3, simTickSentences(sim, tick = 4L, epochMs = 4_000L).size)
    }

    @Test
    fun sim_tick_sentences_hdt_carries_the_sim_heading_not_a_compass() {
        // Mutation target: the HDT source swapped back to the (here, null)
        // compass heading field.
        val sim = GpsSimulator(SimSpec(SimPreset.MAN.lat, SimPreset.MAN.lon, 123.4, 5.0, "MAN"), startedAtMs = 0L)
        val hdt = simTickSentences(sim, tick = 0L, epochMs = 0L).first()
        assertTrue(hdt.startsWith("\$GPHDT,123.4,T*"))
    }

    private fun String.fields(): List<String> = substringAfter('$').substringBefore('*').split(',')

    /** Written independently of [Nmea.checksum] -- see the class doc. */
    private fun independentXor(body: String): String {
        var c = 0
        for (ch in body) c = c xor ch.code
        return "%02X".format(Locale.ROOT, c)
    }
}
