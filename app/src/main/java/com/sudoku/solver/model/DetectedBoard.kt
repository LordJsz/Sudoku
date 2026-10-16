package com.sudoku.solver.model

import android.graphics.Bitmap
import android.graphics.Point

/**
 * Represents a detected Sudoku board from screen capture
 * @param warpedBitmap The perspective-corrected 1400x1400px board image
 * @param cornerPoints The four corner points of the detected board in original image (TL, TR, BR, BL)
 * @param screenBounds The bounding rectangle of the board on the screen
 */
data class DetectedBoard(
    val warpedBitmap: Bitmap,
    val cornerPoints: List<Point>,
    val screenBounds: android.graphics.Rect
) {
    init {
        require(cornerPoints.size == 4) { "Must have exactly 4 corner points" }
    }

    /**
     * Get the top-left corner
     */
    val topLeft: Point get() = cornerPoints[0]

    /**
     * Get the top-right corner
     */
    val topRight: Point get() = cornerPoints[1]

    /**
     * Get the bottom-right corner
     */
    val bottomRight: Point get() = cornerPoints[2]

    /**
     * Get the bottom-left corner
     */
    val bottomLeft: Point get() = cornerPoints[3]

    /**
     * Get the width of the detected board in pixels
     */
    val width: Int get() = screenBounds.width()

    /**
     * Get the height of the detected board in pixels
     */
    val height: Int get() = screenBounds.height()

    /**
     * Release the bitmap to free memory
     */
    fun recycle() {
        if (!warpedBitmap.isRecycled) {
            warpedBitmap.recycle()
        }
    }
}
