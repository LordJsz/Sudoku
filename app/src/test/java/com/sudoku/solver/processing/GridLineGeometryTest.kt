package com.sudoku.solver.processing

import org.junit.Assert.*
import org.junit.Test

class GridLineGeometryTest {
    @Test fun ignoresControlBelowCompleteGrid() {
        val projection = DoubleArray(1600)
        for (i in 0..9) for (p in 359 + i * 78..361 + i * 78) projection[p] = 700.0
        for (p in 1080..1095) projection[p] = 50.0
        assertEquals(listOf(360..1062), GridLineGeometry.spans(projection, 324.0, 9))
    }
    @Test fun rejectsIncompleteGridAndUnevenButtonRows() {
        val projection = DoubleArray(1600)
        for (p in listOf(100, 160, 235, 300, 420, 450, 600, 800, 1100, 1400)) projection[p] = 700.0
        assertTrue(GridLineGeometry.spans(projection, 324.0, 9).isEmpty())
    }
    @Test fun supportsSixteenCells() {
        val projection = DoubleArray(1000)
        for (i in 0..16) projection[100 + i * 40] = 650.0
        assertEquals(listOf(100..740), GridLineGeometry.spans(projection, 324.0, 16))
    }
}
