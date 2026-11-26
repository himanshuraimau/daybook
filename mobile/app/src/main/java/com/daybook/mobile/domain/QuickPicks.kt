package com.daybook.mobile.domain

import com.daybook.mobile.data.LocalExpense
import java.time.LocalDate

/** A one-tap suggestion for the Add expense screen. */
data class QuickPick(val amountMinor: Long, val category: String, val note: String, val isRepeatOfLast: Boolean = false, val count: Int = 1)

object QuickPicks {
    private const val WINDOW_DAYS = 120L

    /**
     * Suggestions from recent history: the most recent expense first ("repeat last"), then the amounts you enter most
     * often (same category, note and amount at least twice). [expenses] must be newest first, as the store returns them.
     */
    fun from(expenses: List<LocalExpense>, today: LocalDate, max: Int = 5): List<QuickPick> {
        val recent = expenses.filter { !LocalDate.parse(it.occurredOn).isBefore(today.minusDays(WINDOW_DAYS)) }
        if (recent.isEmpty() || max <= 0) return emptyList()
        fun key(e: LocalExpense) = Triple(e.category, e.note.trim().lowercase(), e.amountMinor)
        val last = recent.first()
        val result = ArrayList<QuickPick>()
        result.add(QuickPick(last.amountMinor, last.category, last.note.trim(), isRepeatOfLast = true))
        val frequent = recent.groupBy { key(it) }.filter { it.value.size >= 2 && it.key != key(last) }
            .map { (_, list) -> list.first() to list.size }
            .sortedWith(compareByDescending<Pair<LocalExpense, Int>> { it.second }.thenByDescending { it.first.occurredOn })
        for ((e, n) in frequent) { if (result.size >= max) break; result.add(QuickPick(e.amountMinor, e.category, e.note.trim(), false, n)) }
        return result
    }
}
