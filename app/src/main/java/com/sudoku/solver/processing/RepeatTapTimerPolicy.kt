package com.sudoku.solver.processing

/** Pure validation and scheduling rules for the floating repeat-tap timer. */
internal object RepeatTapTimerPolicy {
    const val MIN_INTERVAL_SECONDS = 1L
    const val MAX_INTERVAL_SECONDS = 86_400L

    fun parseIntervalMillis(raw: CharSequence?): Long? {
        val seconds = raw?.toString()?.trim()?.toLongOrNull() ?: return null
        if (seconds !in MIN_INTERVAL_SECONDS..MAX_INTERVAL_SECONDS) return null
        return seconds * 1_000L
    }

    /**
     * Advances from the previous deadline without emitting a burst of overdue taps.
     */
    fun nextDeadline(previousDeadlineMs: Long, nowMs: Long, intervalMs: Long): Long {
        require(intervalMs > 0L) { "Interval must be positive" }
        val firstCandidate = previousDeadlineMs + intervalMs
        if (firstCandidate > nowMs) return firstCandidate
        val missedIntervals = ((nowMs - firstCandidate) / intervalMs) + 1L
        return firstCandidate + missedIntervals * intervalMs
    }
}
