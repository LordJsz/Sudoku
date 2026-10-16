package com.sudoku.solver.processing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VisibleErrorCounterTest {
    @Test fun `reads Spanish error count`() {
        assertEquals(6, VisibleErrorCounter.parse("Errores: 6"))
    }

    @Test fun `reads counter when label and value are separate accessibility nodes`() {
        assertEquals(28, VisibleErrorCounter.parse("Puntaje: 0 Errores:  28 30 de abr"))
    }

    @Test fun `reads English mistake count`() {
        assertEquals(2, VisibleErrorCounter.parse("Mistakes 2 / 3"))
    }

    @Test fun `ignores unrelated numbers`() {
        assertNull(VisibleErrorCounter.parse("Score: 972"))
    }
}
