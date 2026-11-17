package com.sednalabs.solarlab

import org.junit.Assert.*
import org.junit.Test

class LabNumericEditingTest {
    @Test fun equivalentNotationKeepsAuthoredSourceMass() {
        val forms = listOf("1000", "1000.0", "1e3", "1E+03", "+1000.000", " 1000 ")
        for (a in forms) for (b in forms) {
            assertTrue(sameLabNumber(a, b))
            assertEquals(500.0, labEditedSourceMass(a, b, true, true, 500.0), 0.0)
            assertEquals(0.0, labEditedSourceMass(a, b, false, false, 0.0), 0.0)
        }
        for (bad in listOf("", "mass", "NaN", "Infinity", "-Infinity", "1e999")) {
            assertFalse(sameLabNumber(bad, bad))
            assertFalse(sameLabNumber(bad, "1000"))
        }
    }
    @Test fun actualChangesAndGravitySwitchAreApplied() {
        assertFalse(sameLabNumber("1001", "1000"))
        assertEquals(1000.0, labEditedSourceMass("1e3", "1000", true, false, 0.0), 0.0)
        assertEquals(0.0, labEditedSourceMass("1e3", "1000", false, true, 500.0), 0.0)
        assertEquals(2000.0, labEditedSourceMass("2e3", "1000", true, true, 500.0), 0.0)
    }
    @Test fun equivalentDisplayValuesKeepExactNativeBits() {
        for (original in listOf(1.234567890123456e11, -7.654321098765432e10, 29784.123456789, -0.0, Double.MIN_VALUE)) {
            for (offset in listOf(0.0, 1e11, -2e11)) {
                assertEquals(original.toBits(), labEditedScalar("1.25e0", "1.25", original, 1000.0, offset).toBits())
                assertEquals((offset + 2500.0).toBits(), labEditedScalar("2.5", "1.25", original, 1000.0, offset).toBits())
            }
        }
    }
}
