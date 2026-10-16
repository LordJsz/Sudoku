package com.sudoku.solver.processing

import org.junit.Assert.assertEquals
import org.junit.Test

class SixNineMassPolicyTest {
    @Test
    fun `live six ratios are recognized instead of discarded`() {
        assertEquals(6, SixNineMassPolicy.classify(0.472))
        assertEquals(6, SixNineMassPolicy.classify(0.477))
    }

    @Test
    fun `top-heavy digit is recognized as nine`() {
        assertEquals(9, SixNineMassPolicy.classify(0.53))
    }

    @Test
    fun `nearly balanced ink stays unresolved`() {
        assertEquals(0, SixNineMassPolicy.classify(0.49))
        assertEquals(0, SixNineMassPolicy.classify(0.51))
    }
}
