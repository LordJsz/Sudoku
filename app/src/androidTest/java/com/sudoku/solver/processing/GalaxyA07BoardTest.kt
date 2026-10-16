package com.sudoku.solver.processing

import android.graphics.BitmapFactory
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.opencv.android.OpenCVLoader

class GalaxyA07BoardTest {
    @Test fun recognizesFullSixteenBySixteenBoardInsteadOfNineCellFragment() = runBlocking {
        assertTrue(OpenCVLoader.initLocal())
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val screenshot = instrumentation.context.assets.open("galaxy_a07_hexadoku.png").use {
            BitmapFactory.decodeStream(it)
        }
        val board = requireNotNull(BoardDetector().detectBoard(screenshot))
        val recognizer = HexadokuRecognizer(instrumentation.targetContext)
        try {
            assertEquals(350.0, board.screenBounds.top.toDouble(), 3.0)
            assertEquals(1047.0, board.screenBounds.bottom.toDouble(), 3.0)
            assertTrue(board.width > 690)
            val result = recognizer.extractGrid(board.warpedBitmap)
            val rows = listOf(
                "4 B G F 6 0 0 0 0 7 3 E 9 1 5 D",
                "9 0 5 E 1 0 0 G B C 2 D A 0 0 8",
                "0 0 A 3 4 E 9 F G 8 0 0 0 0 0 0",
                "0 0 1 0 0 D 0 B 4 6 0 F 0 0 2 0",
                "0 G 0 0 0 6 B D 0 E 4 C 0 0 9 7",
                "0 D 0 1 0 A 0 8 F B 0 0 4 0 6 G",
                "0 4 7 0 G 0 C 9 0 0 8 0 0 A B 0",
                "0 0 0 B 0 1 0 0 5 0 6 G 0 8 D 3",
                "0 8 0 2 0 0 0 0 0 0 E 0 0 0 G 0",
                "6 0 D A 8 G 0 0 2 9 0 0 3 0 1 F",
                "0 0 0 9 0 0 F 3 0 0 A 0 5 E 0 2",
                "1 F 0 G 0 2 6 4 0 0 0 3 7 B 0 0",
                "0 0 0 4 7 3 0 E D 0 5 0 8 0 C 0",
                "G 5 9 0 0 4 0 0 E 0 0 A 0 0 3 6",
                "2 0 6 0 F 9 0 0 0 0 G 7 0 0 0 1",
                "0 A E 0 0 0 1 0 9 3 0 8 2 G F 4"
            )
            val differences = mutableListOf<String>()
            rows.forEachIndexed { row, symbols ->
                val expected = symbols.split(' ').map { if (it == "G") 16 else it.toInt(16) }.toIntArray()
                expected.forEachIndexed { col, value ->
                    if (value != result.grid.values[row][col]) differences.add(
                        "r${row + 1}c${col + 1}: expected=$value actual=${result.grid.values[row][col]} ${result.cellReads[row to col]}")
                }
            }
            assertTrue(differences.joinToString("\n"), differences.isEmpty())
            assertEquals(HexadokuSolveStatus.SUCCESS, HexadokuSolver().solve(result.grid).status)
        } finally { recognizer.close(); board.recycle(); screenshot.recycle() }
    }

    @Test fun recognizesActualPhoneBoardWithoutChangingClues() = runBlocking {
        assertTrue(OpenCVLoader.initLocal())
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val screenshot = instrumentation.context.assets.open("galaxy_a07_daily.png").use {
            BitmapFactory.decodeStream(it)
        }
        val board = requireNotNull(BoardDetector().detectBoard(screenshot))
        try {
            assertEquals(360.0, board.screenBounds.top.toDouble(), 3.0)
            assertEquals(1061.0, board.screenBounds.bottom.toDouble(), 3.0)
            DigitRecognizer(instrumentation.targetContext).use { recognizer ->
                val result = recognizer.extractGridWithUncertainty(board.warpedBitmap, mlKitOnly = true)
                val expected = listOf("073000002", "869500470", "000007500", "000750800",
                    "026814000", "700236000", "297680004", "380002709", "010073080")
                expected.forEachIndexed { row, digits ->
                    assertArrayEquals("row ${row + 1}", digits.map { it.digitToInt() }.toIntArray(), result.grid.values[row])
                }
                val solved = SudokuSolver().validateAndSolve(result.grid)
                assertEquals(ValidationStatus.SUCCESS, solved.status)
                assertTrue(solved.corrections.isEmpty())
                assertEquals(5, solved.solution!![0][0])
            }
        } finally { board.recycle(); screenshot.recycle() }
    }
}
