package com.sudoku.solver.processing

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Resolution-independent geometry rules shared by capture, OCR, overlays, and input.
 * All inputs are physical pixels except aspect ratios.
 */
internal object ResponsiveGeometryPolicy {
    private const val MIN_CONTOUR_AREA_PX = 1_200.0
    private const val MIN_CONTOUR_AREA_RATIO = 0.005
    private const val MAX_SCAN_ASPECT_LOG_DELTA = 0.16

    fun minimumBoardContourArea(screenWidth: Int, screenHeight: Int): Double {
        if (screenWidth <= 0 || screenHeight <= 0) return MIN_CONTOUR_AREA_PX
        val screenArea = screenWidth.toDouble() * screenHeight.toDouble()
        return max(MIN_CONTOUR_AREA_PX, screenArea * MIN_CONTOUR_AREA_RATIO)
    }

    fun isPlausibleDigitButton(
        buttonWidth: Int,
        buttonHeight: Int,
        screenWidth: Int,
        screenHeight: Int,
        boardWidth: Int,
        boardHeight: Int
    ): Boolean {
        if (buttonWidth <= 0 || buttonHeight <= 0 || screenWidth <= 0 || screenHeight <= 0) {
            return false
        }

        val shortScreenSide = min(screenWidth, screenHeight)
        val boardCell = min(boardWidth, boardHeight).coerceAtLeast(shortScreenSide / 3) / 9.0
        val minimumSide = max(12.0, shortScreenSide * 0.008)
        val maximumWidth = max(screenWidth * 0.45, boardCell * 4.5)
        val maximumHeight = max(screenHeight * 0.25, boardCell * 3.0)

        return buttonWidth >= minimumSide &&
            buttonHeight >= minimumSide &&
            buttonWidth <= maximumWidth &&
            buttonHeight <= maximumHeight
    }

    fun clampOverlayPosition(
        requestedX: Int,
        requestedY: Int,
        screenWidth: Int,
        screenHeight: Int,
        viewWidth: Int,
        viewHeight: Int,
        insetLeft: Int,
        insetTop: Int,
        insetRight: Int,
        insetBottom: Int,
        margin: Int
    ): PixelPosition {
        val safeLeft = insetLeft.coerceAtLeast(0) + margin.coerceAtLeast(0)
        val safeTop = insetTop.coerceAtLeast(0) + margin.coerceAtLeast(0)
        val safeRight = (screenWidth - insetRight.coerceAtLeast(0) - margin.coerceAtLeast(0))
            .coerceAtLeast(safeLeft)
        val safeBottom = (screenHeight - insetBottom.coerceAtLeast(0) - margin.coerceAtLeast(0))
            .coerceAtLeast(safeTop)
        val maxX = (safeRight - viewWidth.coerceAtLeast(0)).coerceAtLeast(safeLeft)
        val maxY = (safeBottom - viewHeight.coerceAtLeast(0)).coerceAtLeast(safeTop)
        return PixelPosition(
            x = requestedX.coerceIn(safeLeft, maxX),
            y = requestedY.coerceIn(safeTop, maxY)
        )
    }

    fun isCompatibleScanAspect(savedAspect: Float, currentAspect: Float): Boolean {
        if (!savedAspect.isFinite() || !currentAspect.isFinite() ||
            savedAspect <= 0f || currentAspect <= 0f
        ) {
            return false
        }
        val logDelta = abs(kotlin.math.ln(savedAspect.toDouble() / currentAspect.toDouble()))
        return logDelta <= MAX_SCAN_ASPECT_LOG_DELTA
    }
}

internal data class PixelPosition(val x: Int, val y: Int)
