package com.kou.otoskop.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class EphemerisTest {

    private val istanbulLat = 41.0082
    private val istanbulLon = 28.9784

    /**
     * Yaz gündönümü, İstanbul meridyen geçişi (~10:00 UTC). Güneş'in yüksekliği
     * fiziksel olarak ~72° (90 - enlem + 23.44) ve güneye (~180°) yakın olmalı.
     */
    @Test fun `sun is high and near south at summer solstice noon`() {
        val t = Instant.parse("2024-06-21T10:00:00Z")
        val bodies = Ephemeris.visibleBodies(istanbulLat, istanbulLon, t, aboveHorizonOnly = false)
        val sun = bodies.first { it.name == "Güneş" }

        assertTrue("Güneş yüksekliği beklenenden uzak: ${sun.altitude}", sun.altitude in 66.0..76.0)
        assertTrue("Güneş azimutu güneye yakın değil: ${sun.azimuth}", sun.azimuth in 150.0..210.0)
    }

    /** Gece yarısı (UTC) Güneş ufuk altında olmalı (negatif yükseklik). */
    @Test fun `sun is below horizon at local midnight`() {
        val t = Instant.parse("2024-06-21T22:00:00Z")  // ~01:00 yerel
        val bodies = Ephemeris.visibleBodies(istanbulLat, istanbulLon, t, aboveHorizonOnly = false)
        val sun = bodies.first { it.name == "Güneş" }
        assertTrue("Güneş gece ufuk üstü görünüyor: ${sun.altitude}", sun.altitude < 0.0)
    }

    /** aboveHorizonOnly=true: dönen tüm cisimlerin yüksekliği > 0 olmalı. */
    @Test fun `aboveHorizon filter only returns visible bodies`() {
        val t = Instant.parse("2024-06-21T10:00:00Z")
        val bodies = Ephemeris.visibleBodies(istanbulLat, istanbulLon, t, aboveHorizonOnly = true)
        assertTrue(bodies.isNotEmpty())
        bodies.forEach { assertTrue("${it.name} ufuk altında: ${it.altitude}", it.altitude > 0.0) }
    }

    /** Azimut/yükseklik makul aralıklarda kalmalı (NaN/taşma olmamalı). */
    @Test fun `all bodies have sane angle ranges`() {
        val t = Instant.parse("2024-03-20T12:00:00Z")
        val bodies = Ephemeris.visibleBodies(istanbulLat, istanbulLon, t, aboveHorizonOnly = false)
        bodies.forEach {
            assertTrue("${it.name} azimut: ${it.azimuth}", it.azimuth in 0.0..360.0)
            assertTrue("${it.name} yükseklik: ${it.altitude}", it.altitude in -90.0..90.0)
        }
        // Güneş + Ay + 5 gezegen = 7 cisim
        assertEquals(7, bodies.size)
    }
}
