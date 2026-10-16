package com.sudoku.solver.processing

import android.graphics.BitmapFactory
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opencv.android.OpenCVLoader
import java.io.File

/** The exact board that displayed the unsafe-OCR banner on 2026-10-14. */
class FireHd10CurrentBoardTest {
    private val expected = listOf(
        "000009000", "093700165", "870010902", "361000584", "740601293",
        "950803671", "607428059", "034970016", "089036007"
    )

    @Test fun ordinaryOcrRecoversCluesSeenOnlyAtTheSmallerScale() = runBlocking {
        assertTrue(OpenCVLoader.initLocal())
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val screenshot = instrumentation.context.assets.open("fire_hd10_9x9_2026_10_14.png").use {
            BitmapFactory.decodeStream(it)
        }
        try {
            val board = requireNotNull(BoardDetector().detectBoard(screenshot))
            try {
                DigitRecognizer(instrumentation.targetContext, useScreenDigitTemplates = false).use { recognizer ->
                    val scan = recognizer.extractGridWithUncertainty(board.warpedBitmap, mlKitOnly = true)
                    expected.forEachIndexed { row, digits ->
                        digits.forEachIndexed { col, digit ->
                            org.junit.Assert.assertEquals("r${row + 1}c${col + 1}",
                                digit.digitToInt(), scan.grid.values[row][col])
                        }
                    }
                    assertTrue(OcrInputSafety.canInput(scan, SudokuSolver().validateAndSolve(scan.grid)))
                }
            } finally { board.recycle() }
        } finally { screenshot.recycle() }
    }

    @Test fun readsEveryVisibleClueWithoutSolverRepair() = runBlocking {
        assertTrue(OpenCVLoader.initLocal())
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val screenshot = instrumentation.context.assets.open("fire_hd10_9x9_2026_10_14.png").use {
            BitmapFactory.decodeStream(it)
        }
        val report = File(instrumentation.targetContext.getExternalFilesDir(null), "current_board_ocr_report.txt")
        val reports = mutableListOf<String>()
        var allCorrect = true
        try {
            val board = requireNotNull(BoardDetector().detectBoard(screenshot))
            try {
                DigitRecognizer(instrumentation.targetContext).use { recognizer ->
                    for (fast in listOf(true, false)) {
                        val started = android.os.SystemClock.elapsedRealtime()
                        val scan = recognizer.extractGridWithUncertainty(board.warpedBitmap, mlKitOnly = fast)
                        val ocrMs = android.os.SystemClock.elapsedRealtime() - started
                        val result = SudokuSolver().validateAndSolve(scan.grid)
                        val differences = expected.flatMapIndexed { row, digits ->
                            digits.mapIndexedNotNull { col, digit ->
                                val actual = scan.grid.values[row][col]
                                val observed = scan.observedDigits[row to col] ?: 0
                                if (actual == digit.digitToInt() && observed == digit.digitToInt()) null else
                                    "r${row + 1}c${col + 1}: expected=$digit actual=$actual observed=$observed"
                            }
                        }
                        val unread = scan.occupiedCells.filter { scan.observedDigits[it] !in 1..9 }
                        val safe = OcrInputSafety.canInput(scan, result)
                        reports.add("fast=$fast ocrMs=$ocrMs bounds=${board.screenBounds} " +
                            "status=${result.status} timeout=${result.solutionIsTimeout} safe=$safe " +
                            "corrections=${result.corrections} unread=$unread differences=$differences\n${scan.grid}")
                        report.writeText(reports.joinToString("\n\n"))
                        allCorrect = allCorrect && differences.isEmpty() && safe
                    }
                }
            } finally { board.recycle() }
        } finally { screenshot.recycle() }
        assertTrue(reports.joinToString("\n\n"), allCorrect)
    }
}
