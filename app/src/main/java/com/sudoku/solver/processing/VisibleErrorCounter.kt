package com.sudoku.solver.processing

/** Extracts a Sudoku app's visible mistake counter in common locales. */
object VisibleErrorCounter {
    private val patterns = listOf(
        Regex("(?i)\\b(?:errors?|mistakes?)\\s*:?\\s*(\\d+)"),
        Regex("(?i)\\b(?:errores?|fallos?)\\s*:?\\s*(\\d+)")
    )

    fun parse(text: CharSequence?): Int? {
        val value = text?.toString()?.trim().orEmpty()
        if (value.isEmpty()) return null
        return patterns.firstNotNullOfOrNull { pattern ->
            pattern.find(value)?.groupValues?.getOrNull(1)?.toIntOrNull()
        }
    }
}
