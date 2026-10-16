package com.sudoku.solver.processing

/** Guards recovery scans from OCR-ing dialogs, banners, and board animations. */
internal object HexadokuScanQualityPolicy {
    private const val MAX_SAFE_UNRECOGNIZED_OCCUPIED_CELLS = 4

    fun isLikelyObstructed(unrecognizedOccupiedCellCount: Int): Boolean =
        unrecognizedOccupiedCellCount > MAX_SAFE_UNRECOGNIZED_OCCUPIED_CELLS
}
