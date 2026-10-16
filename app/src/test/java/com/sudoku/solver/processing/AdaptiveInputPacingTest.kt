package com.sudoku.solver.processing

import org.junit.Assert.assertEquals
import org.junit.Test

class AdaptiveInputPacingTest {
    @Test fun speedProbeKeepsGestureValidAtFastestPace() {
        assertEquals(InputPace(0, 0, 1), InputPace(5, 5, 1).faster())
    }

    @Test fun failedEntryBacksOffAndClampsToSafeRange() {
        assertEquals(InputPace(120, 120, 90), InputPace(115, 110, 85).slower())
        assertEquals(InputPace(45, 40, 11), InputPace(30, 25, 1).slower())
    }

    @Test fun fasterPaceRespectsPreviouslyFailedSpeed() {
        assertEquals(
            InputPace(25, 20, 6),
            InputPace(25, 20, 6).faster(InputPace(25, 20, 6))
        )
    }
}
