package com.sudoku.solver.processing

import android.graphics.BitmapFactory
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.opencv.android.OpenCVLoader
import java.io.File

class PhoneBattleBoardTest {
    @Test fun readsBattleCluesWithoutSolverRepairs() = runBlocking {
        assertTrue(OpenCVLoader.initLocal())
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val screenshot = instrumentation.context.assets.open("phone_battle_9x9.png").use {
            BitmapFactory.decodeStream(it)
        }
        val board = requireNotNull(BoardDetector().detectBoard(screenshot))
        val expected = listOf("794600000", "000975000", "815000906", "607000000",
            "000786029", "400000005", "560800003", "071090008", "000001000")
        val reports = mutableListOf<String>()
        var safeScanFound = false
        try {
            DigitRecognizer(instrumentation.targetContext).use { recognizer ->
                for (fast in listOf(true, false)) {
                    val scan = recognizer.extractGridWithUncertainty(board.warpedBitmap, mlKitOnly = fast)
                    val solved = SudokuSolver().validateAndSolve(scan.grid)
                    val differences = mutableListOf<String>()
                    expected.forEachIndexed { row, digits ->
                        digits.forEachIndexed { col, digit ->
                            if (digit.digitToInt() != scan.grid.values[row][col]) {
                                differences.add("r${row + 1}c${col + 1}: expected=$digit actual=${scan.grid.values[row][col]}")
                            }
                        }
                    }
                    val unread = scan.occupiedCells.filter { scan.observedDigits[it] !in 1..9 }
                    val safe = OcrInputSafety.canInput(scan, solved)
                    safeScanFound = safeScanFound || (safe && differences.isEmpty())
                    reports.add("fast=$fast bounds=${board.screenBounds} status=${solved.status} " +
                        "safe=$safe corrections=${solved.corrections} unread=$unread\n" +
                        "differences=$differences\n${scan.grid}")
                }
            }
            File(instrumentation.targetContext.getExternalFilesDir(null), "battle_ocr_test.txt")
                .writeText(reports.joinToString("\n\n"))
            assertTrue(reports.joinToString("\n\n"), safeScanFound)
        } finally { board.recycle(); screenshot.recycle() }
    }
}
