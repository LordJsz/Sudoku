package com.sudoku.solver.processing

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.opencv.android.OpenCVLoader
import java.io.File

class MotoLargeFontBoardTest {
    private val expected = listOf("008020000", "609008020", "245006198", "900035680",
        "060000050", "850760009", "527600940", "090502806", "080090502")
    private val givens = listOf("008020000", "600008020", "040006190", "900035080",
        "060000050", "050760009", "027600040", "090500006", "000090500")

    @Test fun readsBoardAtDifferentDisplaySizes() = runBlocking {
        assertTrue(OpenCVLoader.initLocal())
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val screenshot = instrumentation.context.assets.open("moto_large_font_9x9.png").use {
            BitmapFactory.decodeStream(it)
        }
        try {
            for (scale in listOf(0.75, 1.5)) {
                val resized = Bitmap.createScaledBitmap(screenshot,
                    (screenshot.width * scale).toInt(), (screenshot.height * scale).toInt(), true)
                try {
                    val board = requireNotNull(BoardDetector().detectBoard(resized))
                    try {
                        DigitRecognizer(instrumentation.targetContext).use { recognizer ->
                            val scan = recognizer.extractGridWithUncertainty(board.warpedBitmap, mlKitOnly = true)
                            expected.forEachIndexed { row, digits ->
                                digits.forEachIndexed { col, digit ->
                                    assertEquals("scale=$scale r${row + 1}c${col + 1}",
                                        digit.digitToInt(), scan.observedDigits[row to col] ?: 0)
                                }
                            }
                            givens.forEachIndexed { row, digits ->
                                assertArrayEquals("scale=$scale givens row=${row + 1}",
                                    digits.map { it.digitToInt() }.toIntArray(), scan.grid.values[row])
                            }
                            assertTrue("scale=$scale", OcrInputSafety.canInput(scan,
                                SudokuSolver().validateAndSolve(scan.grid)))
                        }
                    } finally { board.recycle() }
                } finally { resized.recycle() }
            }
        } finally { screenshot.recycle() }
    }

    @Test fun readsLargeFontBoardWithoutChangingClues() = runBlocking {
        assertTrue(OpenCVLoader.initLocal())
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val screenshot = instrumentation.context.assets.open("moto_large_font_9x9.png").use {
            BitmapFactory.decodeStream(it)
        }
        val reports = mutableListOf<String>()
        try {
            val board = requireNotNull(BoardDetector().detectBoard(screenshot))
            try {
                assertEquals(391.0, board.screenBounds.top.toDouble(), 3.0)
                assertEquals(1091.0, board.screenBounds.bottom.toDouble(), 3.0)
                DigitRecognizer(instrumentation.targetContext).use { recognizer ->
                    for (fast in listOf(true, false)) {
                        val scan = recognizer.extractGridWithUncertainty(board.warpedBitmap, mlKitOnly = fast)
                        val solved = SudokuSolver().validateAndSolve(scan.grid)
                        val differences = mutableListOf<String>()
                        expected.forEachIndexed { row, digits ->
                            digits.forEachIndexed { col, digit ->
                                val actual = scan.observedDigits[row to col] ?: 0
                                if (digit.digitToInt() != actual) differences.add(
                                    "r${row + 1}c${col + 1}: expected=$digit actual=$actual")
                            }
                        }
                        reports.add("fast=$fast bounds=${board.screenBounds} safe=${OcrInputSafety.canInput(scan, solved)} " +
                            "status=${solved.status} corrections=${solved.corrections} " +
                            "unread=${scan.occupiedCells.filter { scan.observedDigits[it] !in 1..9 }}\n" +
                            "differences=$differences\n${scan.grid}")
                        File(instrumentation.targetContext.getExternalFilesDir(null), "moto_ocr_test.txt")
                            .writeText(reports.joinToString("\n\n"))
                        assertTrue(reports.joinToString("\n\n"), differences.isEmpty())
                        givens.forEachIndexed { row, digits ->
                            assertArrayEquals("fast=$fast givens row=${row + 1}",
                                digits.map { it.digitToInt() }.toIntArray(), scan.grid.values[row])
                        }
                        assertTrue(reports.joinToString("\n\n"), OcrInputSafety.canInput(scan, solved))
                    }
                }
            } finally { board.recycle() }
        } finally { screenshot.recycle() }
    }
}
