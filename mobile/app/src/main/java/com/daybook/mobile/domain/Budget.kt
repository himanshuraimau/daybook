package com.daybook.mobile.domain

import com.daybook.mobile.data.LocalExpense
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

data class FixedSpend(val id: String, val name: String, val amountMinor: Long, val category: String)

data class BudgetProfile(val incomeMinor: Long, val payday: Int, val fixed: List<FixedSpend>, val updatedAt: Long) {
    val fixedMinor: Long get() = fixed.sumOf { it.amountMinor }
    /** Salary minus fixed spends; negative when fixed spends exceed the salary. */
    val spendableMinor: Long get() = incomeMinor - fixedMinor
}

/** A pay cycle: [start] and [end] are both inclusive local dates. */
data class Cycle(val start: LocalDate, val end: LocalDate) {
    val days: Int get() = ChronoUnit.DAYS.between(start, end).toInt() + 1
    operator fun contains(date: LocalDate) = !date.isBefore(start) && !date.isAfter(end)
    fun months(): List<YearMonth> = generateSequence(YearMonth.from(start)) { it.plusMonths(1) }.takeWhile { it <= YearMonth.from(end) }.toList()
}

object Cycles {
    /** Payday inside [month]; a payday later than the month's length falls on its last day. */
    fun payDate(month: YearMonth, payday: Int): LocalDate = month.atDay(minOf(payday.coerceIn(1, 31), month.lengthOfMonth()))

    fun containing(date: LocalDate, payday: Int): Cycle {
        val thisMonth = YearMonth.from(date)
        val start = if (!date.isBefore(payDate(thisMonth, payday))) payDate(thisMonth, payday) else payDate(thisMonth.minusMonths(1), payday)
        return startingOn(start, payday)
    }
    private fun startingOn(start: LocalDate, payday: Int): Cycle =
        Cycle(start, payDate(YearMonth.from(start).plusMonths(1), payday).minusDays(1))
    fun previous(cycle: Cycle, payday: Int): Cycle = containing(cycle.start.minusDays(1), payday)
    fun next(cycle: Cycle, payday: Int): Cycle = containing(cycle.end.plusDays(1), payday)
}

data class CategoryTotal(val category: String, val amountMinor: Long)

data class CycleSummary(
    val cycle: Cycle,
    val spentMinor: Long,
    val todayMinor: Long,
    val categories: List<CategoryTotal>,
    val perDay: Map<LocalDate, Long>,
    val biggest: LocalExpense?,
    /** Days from the cycle start through today (or the whole cycle once it has ended). */
    val elapsedDays: Int,
    val noSpendDays: Int,
    val averagePerDayMinor: Long,
    /** Percent change against the previous cycle up to the same day; null when the previous cycle had no spending. */
    val vsPreviousPercent: Int?,
    val budget: BudgetFigures?,
)

data class BudgetFigures(
    val fixedMinor: Long,
    val spendableMinor: Long,
    val leftMinor: Long,
    val daysLeft: Int,
    /** Left ÷ days remaining including today; 0 when nothing is left. */
    val dailyAllowanceMinor: Long,
    /** Spendable minus the spending projected for the whole cycle; positive means under budget. */
    val projectedFinishMinor: Long,
    val usedPercent: Int,
) { val over: Boolean get() = leftMinor < 0 }

object Insights {
    fun summarize(expenses: List<LocalExpense>, profile: BudgetProfile?, today: LocalDate, cycle: Cycle, previous: Cycle? = null): CycleSummary {
        val inCycle = expenses.filter { LocalDate.parse(it.occurredOn) in cycle }
        val spent = inCycle.sumOf { it.amountMinor }
        val perDay = inCycle.groupBy { LocalDate.parse(it.occurredOn) }.mapValues { (_, list) -> list.sumOf { it.amountMinor } }
        val elapsed = elapsedDays(cycle, today)
        val noSpend = if (elapsed == 0) 0 else (0 until elapsed).count { cycle.start.plusDays(it.toLong()) !in perDay }
        val average = if (elapsed == 0) 0 else spent / elapsed
        val vs = previous?.let { prev ->
            val prevEnd = prev.start.plusDays(elapsed.toLong() - 1L).let { if (it.isAfter(prev.end)) prev.end else it }
            val prevSpent = expenses.filter { LocalDate.parse(it.occurredOn).let { d -> !d.isBefore(prev.start) && !d.isAfter(prevEnd) } }.sumOf { it.amountMinor }
            if (prevSpent <= 0L || elapsed == 0) null else Math.round((spent - prevSpent) * 100.0 / prevSpent).toInt()
        }
        val figures = profile?.let { p ->
            val left = p.spendableMinor - spent
            val daysLeft = if (today.isAfter(cycle.end)) 0 else if (today.isBefore(cycle.start)) cycle.days else (ChronoUnit.DAYS.between(today, cycle.end).toInt() + 1)
            val projectedTotal = if (elapsed == 0) 0L else spent * cycle.days / elapsed
            val used = if (p.spendableMinor <= 0L) (if (spent > 0) 100 else 0) else Math.min(999L, spent * 100 / p.spendableMinor).toInt()
            BudgetFigures(p.fixedMinor, p.spendableMinor, left, daysLeft, if (daysLeft > 0 && left > 0) left / daysLeft else 0L, p.spendableMinor - projectedTotal, used)
        }
        return CycleSummary(
            cycle = cycle, spentMinor = spent, todayMinor = perDay[today] ?: 0L,
            categories = inCycle.groupBy { it.category }.map { (c, l) -> CategoryTotal(c, l.sumOf { it.amountMinor }) }.sortedByDescending { it.amountMinor },
            perDay = perDay, biggest = inCycle.maxByOrNull { it.amountMinor }, elapsedDays = elapsed, noSpendDays = noSpend,
            averagePerDayMinor = average, vsPreviousPercent = vs, budget = figures,
        )
    }
    fun elapsedDays(cycle: Cycle, today: LocalDate): Int = when {
        today.isBefore(cycle.start) -> 0
        today.isAfter(cycle.end) -> cycle.days
        else -> ChronoUnit.DAYS.between(cycle.start, today).toInt() + 1
    }
}

object Streak {
    /** Consecutive logged days ending today, or ending yesterday while today is still empty. */
    fun current(logged: Set<LocalDate>, today: LocalDate): Int {
        var day = if (today in logged) today else today.minusDays(1)
        var count = 0
        while (day in logged) { count++; day = day.minusDays(1) }
        return count
    }
    /** The seven days ending today, oldest first, each flagged when something was logged. */
    fun week(logged: Set<LocalDate>, today: LocalDate): List<Pair<LocalDate, Boolean>> =
        (6 downTo 0).map { today.minusDays(it.toLong()).let { d -> d to (d in logged) } }
}
