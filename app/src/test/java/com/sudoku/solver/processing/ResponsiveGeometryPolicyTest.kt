package com.sudoku.solver.processing

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class ResponsiveGeometryPolicyTest {
    private val deviceSizes = listOf(
        Pair(480, 800),
        Pair(720, 1280),
        Pair(1080, 2400),
        Pair(1200, 1920),
        Pair(1600, 2560),
        Pair(2560, 1600),
        Pair(1768, 2208)
    )

    @Test
    fun `board contour threshold scales across phones tablets and landscape`() {
        for ((width, height) in deviceSizes) {
            val shortSide = minOf(width, height)
            val realisticBoardArea = (shortSide * 0.72) * (shortSide * 0.72)
            val threshold = ResponsiveGeometryPolicy.minimumBoardContourArea(width, height)
            assertTrue("$width x $height threshold", threshold >= 1_200.0)
            assertTrue("$width x $height board remains detectable", threshold < realisticBoardArea)
        }
    }

    @Test
    fun `typical keypad buttons remain valid at every tested resolution`() {
        for ((width, height) in deviceSizes) {
            val boardSide = (minOf(width, height) * 0.88).toInt()
            val keyWidth = (width / 10.5).toInt()
            val keyHeight = (minOf(width, height) / 9.0).toInt()
            assertTrue(
                "$width x $height keypad",
                ResponsiveGeometryPolicy.isPlausibleDigitButton(
                    keyWidth,
                    keyHeight,
                    width,
                    height,
                    boardSide,
                    boardSide
                )
            )
        }
    }

    @Test
    fun `screen-sized panels are not mistaken for digit buttons`() {
        assertFalse(
            ResponsiveGeometryPolicy.isPlausibleDigitButton(
                buttonWidth = 900,
                buttonHeight = 500,
                screenWidth = 1080,
                screenHeight = 2400,
                boardWidth = 1000,
                boardHeight = 1000
            )
        )
    }

    @Test
    fun `overlay stays inside cutouts and system bars on narrow phone`() {
        val clamped = ResponsiveGeometryPolicy.clampOverlayPosition(
            requestedX = 1000,
            requestedY = -50,
            screenWidth = 1080,
            screenHeight = 2400,
            viewWidth = 430,
            viewHeight = 150,
            insetLeft = 0,
            insetTop = 96,
            insetRight = 0,
            insetBottom = 120,
            margin = 24
        )
        assertEquals(626, clamped.x)
        assertEquals(120, clamped.y)
    }

    @Test
    fun `custom scan area survives similar screen but not rotation`() {
        assertTrue(ResponsiveGeometryPolicy.isCompatibleScanAspect(0.5625f, 0.55f))
        assertFalse(ResponsiveGeometryPolicy.isCompatibleScanAspect(0.5625f, 1.7778f))
        assertFalse(ResponsiveGeometryPolicy.isCompatibleScanAspect(Float.NaN, 0.5625f))
    }
}
