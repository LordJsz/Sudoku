package com.sudoku.solver.processing

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HexadokuScanQualityPolicyTest {
    @Test
    fun `a few protected unread clues can continue to the unique solver`() {
        assertFalse(HexadokuScanQualityPolicy.isLikelyObstructed(0))
        assertFalse(HexadokuScanQualityPolicy.isLikelyObstructed(4))
    }

    @Test
    fun `many unread occupied cells indicate a covered or animating board`() {
        assertTrue(HexadokuScanQualityPolicy.isLikelyObstructed(5))
        assertTrue(HexadokuScanQualityPolicy.isLikelyObstructed(11))
    }
}
