package com.sudoku.solver.processing

import org.junit.Assert.*
import org.junit.Test

class HexadokuOneTopologyPolicyTest {
    @Test fun acceptsNarrowVerticalOne() = assertTrue(HexadokuOneTopologyPolicy.isOne(0.44, 0.95))
    @Test fun rejectsSlantedSeven() = assertFalse(HexadokuOneTopologyPolicy.isOne(0.52, 0.43))
    @Test fun rejectsWideGlyph() = assertFalse(HexadokuOneTopologyPolicy.isOne(0.75, 0.95))
}
