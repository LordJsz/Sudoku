package com.sudoku.solver.processing

internal object HexadokuOneTopologyPolicy {
    fun isOne(aspect: Double, verticalStemRatio: Double): Boolean =
        // A scaled or lightly antialiased glyph can break the longest vertical
        // run into short gaps. Keep the width guard to reject 7s, and allow a
        // lower continuous-stem ratio for narrow fonts.
        aspect in 0.15..0.58 && verticalStemRatio >= 0.62
}
