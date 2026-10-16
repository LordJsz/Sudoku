package com.sudoku.solver.processing

import android.graphics.BitmapFactory
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opencv.android.OpenCVLoader
import java.io.File

class FireHd10NineBoardTest {
    private val visible = listOf(
        "000000007", "007301002", "006097800", "000070208", "508030000",
        "010082000", "000760090", "000950000", "040000000"
    )
    private val givens = listOf(
        "000000007", "007301002", "006090800", "000000208", "508030000",
        "010082000", "000760090", "000950000", "040000000"
    )

    @Test fun readsPrintedNineAndPreservesPlayerEntries() = runBlocking {
        assertTrue(OpenCVLoader.initLocal())
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val screenshot = instrumentation.context.assets.open("fire_hd10_9x9_2026_10_11.png").use {
            BitmapFactory.decodeStream(it)
        }
        try {
            val board = requireNotNull(BoardDetector().detectBoard(screenshot))
            try {
                DigitRecognizer(instrumentation.targetContext).use { recognizer ->
                    val reports = mutableListOf<String>()
                    for (fast in listOf(true, false)) {
                        val scan = recognizer.extractGridWithUncertainty(board.warpedBitmap, mlKitOnly = fast)
                        val solved = SudokuSolver().validateAndSolve(scan.grid)
                        val differences = visible.flatMapIndexed { row, digits ->
                            digits.mapIndexedNotNull { col, digit ->
                                val actual = scan.observedDigits[row to col] ?: 0
                                if (actual == digit.digitToInt()) null else
                                    "r${row + 1}c${col + 1}: expected=$digit actual=$actual"
                            }
                        }
                        reports.add("fast=$fast bounds=${board.screenBounds} safe=${OcrInputSafety.canInput(scan, solved)} " +
                            "status=${solved.status} corrections=${solved.corrections} differences=$differences")
                        File(instrumentation.targetContext.getExternalFilesDir(null), "fire_hd10_9x9_report.txt")
                            .writeText(reports.joinToString("\n"))
                        assertTrue(reports.joinToString("\n"), differences.isEmpty())
                        givens.forEachIndexed { row, digits ->
                            assertArrayEquals("fast=$fast row=${row + 1}",
                                digits.map { it.digitToInt() }.toIntArray(), scan.grid.values[row])
                        }
                        assertEquals(reports.joinToString("\n"), 9, scan.observedDigits[7 to 3])
                        assertTrue(reports.joinToString("\n"), OcrInputSafety.canInput(scan, solved))
                    }
                }
            } finally { board.recycle() }
        } finally { screenshot.recycle() }
    }
}
