package com.sudoku.solver.processing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HexadokuBarTopologyPolicyTest {
    @Test
    fun `full left stem and three bars match E from the A07 capture`() {
        assertEquals(
            HexadokuBarTopologyPolicy.Symbol.E,
            HexadokuBarTopologyPolicy.classify(
                enclosedHoleCount = 0,
                leftStemRunRatio = 0.994,
                topBarDensity = 0.551,
                middleBarDensity = 0.305,
                bottomBarDensity = 0.302
            )
        )
    }

    @Test
    fun `full left stem and no bottom bar match F`() {
        assertEquals(
            HexadokuBarTopologyPolicy.Symbol.F,
            HexadokuBarTopologyPolicy.classify(0, 0.98, 0.52, 0.28, 0.03)
        )
    }

    @Test
    fun `enclosed glyph is not mistaken for E or F`() {
        assertNull(HexadokuBarTopologyPolicy.classify(1, 0.98, 0.52, 0.28, 0.31))
    }

    @Test
    fun `ambiguous lower bar is not guessed`() {
        assertNull(HexadokuBarTopologyPolicy.classify(0, 0.98, 0.52, 0.28, 0.13))
    }

    @Test
    fun `short left edge is not mistaken for a bar letter`() {
        assertNull(HexadokuBarTopologyPolicy.classify(0, 0.65, 0.52, 0.28, 0.31))
    }
}
