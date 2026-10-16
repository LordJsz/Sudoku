package com.sudoku.solver.processing

import android.graphics.BitmapFactory
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opencv.android.OpenCVLoader

class FireHd10BoardTest {
    @Test
    fun readsOpenCWithoutTurningItIntoSix() = runBlocking {
        assertTrue(OpenCVLoader.initLocal())
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val screenshot = instrumentation.context.assets.open("fire_hd10_c6_regression.png").use {
            BitmapFactory.decodeStream(it)
        }
        val board = requireNotNull(BoardDetector().detectBoard(screenshot))
        val recognizer = HexadokuRecognizer(instrumentation.targetContext)
        try {
            val result = recognizer.extractGrid(board.warpedBitmap)
            assertEquals(132, result.occupiedCells.size)
            assertEquals(132, result.grid.filledCount())
            assertEquals(6, result.grid.values[10][5])
            assertEquals(12, result.grid.values[10][11])
            assertEquals("6", result.cellReads[10 to 11]?.tesseractText)
            assertEquals(12, result.cellReads[10 to 11]?.value)
            val solution = HexadokuSolver().solve(result.grid)
            assertEquals(solution.message, HexadokuSolveStatus.SUCCESS, solution.status)
            assertNotNull(solution.solution)
        } finally {
            recognizer.close()
            board.recycle()
            screenshot.recycle()
        }
    }
}
