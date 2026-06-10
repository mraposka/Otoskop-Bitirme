package com.kou.otoskop.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AstroMathTest {

    @Test fun `normalizeAzimuth wraps negative values to positive`() {
        assertEquals(350.0, AstroMath.normalizeAzimuth(-10.0), 0.001)
        assertEquals(10.0, AstroMath.normalizeAzimuth(370.0), 0.001)
    }

    @Test fun `cardinal returns Turkish direction labels`() {
        assertEquals("K", AstroMath.cardinal(0.0))
        assertEquals("D", AstroMath.cardinal(90.0))
        assertEquals("G", AstroMath.cardinal(180.0))
        assertEquals("B", AstroMath.cardinal(270.0))
        assertEquals("KD", AstroMath.cardinal(45.0))
    }

    @Test fun `azimuthDelta handles wrap around`() {
        assertEquals(20.0, AstroMath.azimuthDelta(350.0, 10.0), 0.001)
        assertEquals(-20.0, AstroMath.azimuthDelta(10.0, 350.0), 0.001)
    }

    @Test fun `inWindow handles wrap around region 350 to 10`() {
        assertTrue(
            AstroMath.inWindow(
                azimuth = 5.0, altitude = 40.0,
                azMin = 350.0, azMax = 10.0,
                altMin = 30.0, altMax = 60.0,
            )
        )
        assertFalse(
            AstroMath.inWindow(
                azimuth = 180.0, altitude = 40.0,
                azMin = 350.0, azMax = 10.0,
                altMin = 30.0, altMax = 60.0,
            )
        )
    }
}
