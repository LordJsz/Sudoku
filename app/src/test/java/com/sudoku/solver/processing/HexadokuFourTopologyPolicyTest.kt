package com.sudoku.solver.processing

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HexadokuFourTopologyPolicyTest {
    @Test
    fun `centered counter crossbar and full right stem match four`() {
        assertTrue(HexadokuFourTopologyPolicy.matches(1, 0.50, 0.72, 0.70, 1.0))
    }

    @Test
    fun `wide glyph with bottom bar stays classified as A`() {
        assertFalse(HexadokuFourTopologyPolicy.matches(1, 0.47, 0.87, 0.97, 0.46))
    }

    @Test
    fun `high loop and shorter right tail stay classified as nine`() {
        assertFalse(HexadokuFourTopologyPolicy.matches(1, 0.34, 0.62, 0.32, 0.79))
    }

    @Test
    fun `multiple counters do not match four`() {
        assertFalse(HexadokuFourTopologyPolicy.matches(2, 0.50, 0.72, 0.70, 1.0))
    }

    @Test
    fun `short right stem does not match four`() {
        assertFalse(HexadokuFourTopologyPolicy.matches(1, 0.50, 0.72, 0.70, 0.60))
    }
}
