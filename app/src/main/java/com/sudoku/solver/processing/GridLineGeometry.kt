package com.sudoku.solver.processing

import kotlin.math.abs

/** Finds complete, evenly spaced grid lines; nearby buttons cannot extend the board. */
internal object GridLineGeometry {
    fun spans(
        projection: DoubleArray,
        threshold: Double,
        cells: Int,
        minimumCellStep: Double = 12.0
    ): List<IntRange> {
        val centers = mutableListOf<Int>()
        var index = 0
        while (index < projection.size) {
            if (projection[index] < threshold) { index++; continue }
            val start = index
            while (index + 1 < projection.size && projection[index + 1] >= threshold) index++
            centers.add((start + index) / 2)
            index++
        }
        val result = mutableListOf<IntRange>()
        for (start in centers.indices) {
            for (end in start + cells until centers.size) {
                val step = (centers[end] - centers[start]).toDouble() / cells
                if (step < minimumCellStep) continue
                val tolerance = maxOf(2.0, step * 0.055)
                if ((1 until cells).all { line ->
                        centers.any { abs(it - (centers[start] + line * step)) <= tolerance }
                    }) result.add(centers[start]..centers[end])
            }
        }
        return result
    }
}
