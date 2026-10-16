package com.sudoku.solver.processing

import org.junit.Assert.assertEquals
import org.junit.Test

class OcrDigitStylePolicyTest {
    @Test
    fun `black digit on blue highlighted cell stays neutral`() {
        val pixels = syntheticCell(
            background = rgb(135, 174, 253),
            ink = rgb(42, 45, 49),
            antialiasInk = rgb(75, 92, 128)
        )

        assertEquals(OcrDigitStyle.NONE, OcrDigitStylePolicy.classifyArgbPixels(pixels))
    }

    @Test
    fun `blue digit on blue highlighted cell remains blue`() {
        val pixels = syntheticCell(
            background = rgb(135, 174, 253),
            ink = rgb(42, 91, 190),
            antialiasInk = rgb(65, 112, 205)
        )

        assertEquals(OcrDigitStyle.BLUE, OcrDigitStylePolicy.classifyArgbPixels(pixels))
    }

    @Test
    fun `blue digit on white cell remains blue`() {
        val pixels = syntheticCell(
            background = rgb(255, 255, 255),
            ink = rgb(42, 91, 190),
            antialiasInk = rgb(80, 122, 207)
        )

        assertEquals(OcrDigitStyle.BLUE, OcrDigitStylePolicy.classifyArgbPixels(pixels))
    }

    @Test
    fun `red digit is recognized without treating white background as ink`() {
        val pixels = syntheticCell(
            background = rgb(255, 255, 255),
            ink = rgb(205, 48, 45),
            antialiasInk = rgb(220, 90, 86)
        )

        assertEquals(OcrDigitStyle.RED, OcrDigitStylePolicy.classifyArgbPixels(pixels))
    }

    private fun syntheticCell(
        background: Int,
        ink: Int,
        antialiasInk: Int
    ): IntArray {
        return IntArray(2_000) { index ->
            when {
                index < 400 -> ink
                index < 500 -> antialiasInk
                else -> background
            }
        }
    }

    private fun rgb(red: Int, green: Int, blue: Int): Int {
        return (0xff shl 24) or (red shl 16) or (green shl 8) or blue
    }
}
