package com.sudoku.solver.processing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HexadokuKeypadGeometryTest {
    @Test
    fun `digit fallback lands inside A07 keypad buttons`() {
        assertEquals(Pair(40, 1351), a07Point(1))
        assertEquals(Pair(120, 1351), a07Point(2))
        assertEquals(Pair(680, 1351), a07Point(9))
    }

    @Test
    fun `letter fallback spreads A through G across second page`() {
        assertEquals(Pair(51, 1351), a07Point(10))
        assertEquals(Pair(668, 1351), a07Point(16))
    }

    @Test
    fun `visible keypad center takes precedence over estimated height`() {
        assertEquals(
            Pair(120, 1363),
            HexadokuKeypadGeometry.fallbackTapPoint(2, 720, 1600, 1048, 1363)
        )
    }

    @Test
    fun `rejects invalid symbols and dimensions`() {
        assertNull(HexadokuKeypadGeometry.fallbackTapPoint(0, 720, 1600, 1048))
        assertNull(HexadokuKeypadGeometry.fallbackTapPoint(17, 720, 1600, 1048))
        assertNull(HexadokuKeypadGeometry.fallbackTapPoint(2, 0, 1600, 1048))
    }

    private fun a07Point(value: Int): Pair<Int, Int>? =
        HexadokuKeypadGeometry.fallbackTapPoint(value, 720, 1600, 1048)
}
