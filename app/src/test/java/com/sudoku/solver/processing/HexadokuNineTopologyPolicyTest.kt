package com.sudoku.solver.processing

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HexadokuNineTopologyPolicyTest {
    @Test
    fun `upper loop and lower right tail match nine`() {
        assertTrue(HexadokuNineTopologyPolicy.matches(1, 0.34, 210, 640))
    }

    @Test
    fun `lower loop stays classified as six`() {
        assertFalse(HexadokuNineTopologyPolicy.matches(1, 0.67, 510, 620))
    }

    @Test
    fun `balanced lower legs do not match nine`() {
        assertFalse(HexadokuNineTopologyPolicy.matches(1, 0.39, 450, 500))
    }

    @Test
    fun `zero or multiple holes do not match nine`() {
        assertFalse(HexadokuNineTopologyPolicy.matches(0, 0.34, 210, 640))
        assertFalse(HexadokuNineTopologyPolicy.matches(2, 0.34, 210, 640))
    }
}
