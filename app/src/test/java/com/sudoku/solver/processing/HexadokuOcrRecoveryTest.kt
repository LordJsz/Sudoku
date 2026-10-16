package com.sudoku.solver.processing

import com.sudoku.solver.model.HexadokuGrid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HexadokuOcrRecoveryTest {
    @Test
    fun `live Fire tablet grid solves when the visible C is read correctly`() {
        val grid = parseGrid(
            """
            G . . 3 5 . . . B 7 . D 4 . 9 .
            . 1 D F G E 6 2 . 3 8 . 7 . . .
            . 7 . . 3 B C F 4 . . . D G . .
            . . 8 A . 4 . . G . E . . 2 1 3
            . 5 7 8 . . . . . . . 4 E 3 C D
            C . . . 7 . 4 5 2 A F E G . . .
            . . G 9 . . A B 6 5 . . F 4 . 8
            . . F 4 9 . . E . . . G . . 5 .
            E . 1 . . F B 3 . D G 5 . C A 4
            3 . . . . 9 G 8 F 4 . 2 1 . . 5
            . . . 2 A 6 . . . . . 6 . . 7 G
            . . . . . 7 . . . 9 A 8 . . . E
            7 . A B 1 G 9 . 8 2 . . . D . 6
            . . E C 8 . . . . G . A B F 3 1
            8 . 2 . F C 3 . . E 1 . . A G .
            9 . . . B . . A 5 . 7 . . . 4 2
            """.trimIndent()
        )

        val corrected = grid.copy().also { it.values[10][11] = 12 }
        assertEquals(HexadokuSolveStatus.INVALID, HexadokuSolver().solve(grid).status)
        val result = HexadokuSolver().solve(corrected)
        assertEquals(HexadokuSolveStatus.SUCCESS, result.status)
        assertEquals(12, result.solution!![10][11])
    }

    @Test
    fun `recovers the contradictory OCR reads from the current puzzle`() {
        val grid = parseGrid(
            """
            G E A 7 D 5 9 . 4 . . 7 B 8 . F
            8 7 . . . 1 6 G 5 9 B . A . C .
            . F 6 . . 2 7 B . D . A 1 . 3 5
            . . . . . A . . . 6 3 8 . . E G
            1 B . 2 . 4 3 . . F G 9 C A . E
            . . 4 . . . . D . 1 . . . . 7 .
            A . . . 2 9 F 8 . . 5 3 D G . .
            3 . 8 . . . . C . . D E . . . .
            . . C . . G . 1 . . . . E D F .
            . . . 8 6 . B 9 F . . 5 2 3 G .
            F 2 G . . D 4 E 8 . . . . B A C
            . 4 E . . . . A 3 G . . . . . .
            5 . F . . . . 4 B 2 . C . E D .
            E . . . B 7 D . . 5 F . G . 9 .
            . C B 7 . . 8 6 . 3 . . 5 F 4 .
            . A 9 D . . . 1 . 8 1 . . . B 2
            """.trimIndent()
        )

        val recovery = HexadokuOcrRecovery.recover(grid, HexadokuSolver())

        assertNotNull(recovery)
        requireNotNull(recovery)
        assertEquals(HexadokuSolveStatus.SUCCESS, recovery.solveResult.status)
        assertEquals(setOf(0 to 3, 15 to 7), recovery.clearedCells)
        assertEquals(0, recovery.grid.values[0][3])
        assertEquals(0, recovery.grid.values[15][7])
        assertTrue(recovery.grid.locked[0][3])
        assertTrue(recovery.grid.locked[15][7])
    }

    @Test
    fun `does not guess when clearing either duplicate leaves multiple solutions`() {
        val values = Array(HexadokuGrid.SIZE) { IntArray(HexadokuGrid.SIZE) }
        values[0][0] = 1
        values[0][1] = 1
        val locked = Array(HexadokuGrid.SIZE) { row ->
            BooleanArray(HexadokuGrid.SIZE) { col -> values[row][col] != 0 }
        }

        val recovery = HexadokuOcrRecovery.recover(
            HexadokuGrid(values, locked),
            HexadokuSolver()
        )

        assertNull(recovery)
    }

    @Test
    fun `ignores a grid without duplicate OCR conflicts`() {
        val values = Array(HexadokuGrid.SIZE) { IntArray(HexadokuGrid.SIZE) }
        val locked = Array(HexadokuGrid.SIZE) { BooleanArray(HexadokuGrid.SIZE) }

        assertTrue(HexadokuOcrRecovery.duplicateConflictCells(values).isEmpty())
        assertNull(
            HexadokuOcrRecovery.recover(
                HexadokuGrid(values, locked),
                HexadokuSolver()
            )
        )
    }

    private fun parseGrid(raw: String): HexadokuGrid {
        val rows = raw.lines().map { line ->
            line.trim().split(Regex("\\s+")).map { symbol ->
                if (symbol == ".") 0 else HexadokuGrid.valueFor(symbol.single())
            }.toIntArray()
        }.toTypedArray()
        val locked = Array(HexadokuGrid.SIZE) { row ->
            BooleanArray(HexadokuGrid.SIZE) { col -> rows[row][col] != 0 }
        }
        return HexadokuGrid(rows, locked)
    }
}
