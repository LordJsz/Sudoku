package com.sudoku.solver.processing

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HexadokuEightTopologyPolicyTest {
    @Test
    fun `two counters and curved left edge match eight`() {
        assertTrue(HexadokuEightTopologyPolicy.matches(2, 0.47))
    }

    @Test
    fun `full height left stem stays classified as B`() {
        assertFalse(HexadokuEightTopologyPolicy.matches(2, 1.0))
    }

    @Test
    fun `one counter does not match eight`() {
        assertFalse(HexadokuEightTopologyPolicy.matches(1, 0.47))
    }

    @Test
    fun `three counters do not match eight`() {
        assertFalse(HexadokuEightTopologyPolicy.matches(3, 0.47))
    }
}
