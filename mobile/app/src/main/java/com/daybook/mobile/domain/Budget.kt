package com.daybook.mobile.domain

import com.daybook.mobile.data.LocalExpense
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

/**
 * A monthly commitment set aside every cycle. When [months] and [startKey] are set it is an EMI: it is charged only
 * in the [months] cycles beginning with the one whose start month is [startKey] ("2026-11").
 */
data class FixedSpend(
    val id: String, val name: String, val amountMinor: Long, val category: String,
    val months: Int? = null, val startKey: String? = null,
) {
    val isEmi: Boolean get() = months != null && startKey != null
    val totalMinor: Long get() = amountMinor * (months ?: 1)
    /** 1-based instalment number of [cycle]; at or below 0 before the EMI starts, above [months] once finished. */
    fun instalmentIn(cycle: Cycle): Int =
        if (!isEmi) 1 else ChronoUnit.MONTHS.between(YearMonth.parse(startKey), YearMonth.from(cycle.start)).toInt() + 1
    fun chargedIn(cycle: Cycle): Boolean = !isEmi || instalmentIn(cycle) in 1..months!!
}

/** A one-off payment received on [date] (a bonus, freelance work). It raises the spendable amount of the cycle that contains the date. */
data class OneOffIncome(val id: String, val name: String, val amountMinor: Long, val date: String) {
    fun inCycle(cycle: Cycle): Boolean = LocalDate.parse(date) in cycle
}

data class BudgetProfile(
    val incomeMinor: Long, val payday: Int, val fixed: List<FixedSpend>, val updatedAt: Long,
    /** Amount to keep aside every cycle; it is subtracted from what can be spent. */
    val savingsMinor: Long = 0L,
    val incomes: List<OneOffIncome> = emptyList(),
) {
    /** Every fixed spend added up, ignoring when an EMI starts or ends. Use [fixedFor] for a particular cycle. */
    val fixedMinor: Long get() = fixed.sumOf { it.amountMinor }
    /** Salary minus all fixed spends and the savings target, ignoring one-off income. Use [spendableFor] for a particular cycle. */
    val spendableMinor: Long get() = incomeMinor - fixedMinor - savingsMinor
    /** Fixed spends and EMI instalments that fall in [cycle]. */
    fun fixedFor(cycle: Cycle): Long = fixed.filter { it.chargedIn(cycle) }.sumOf { it.amountMinor }
    /** One-off income received within [cycle]. */
    fun extraFor(cycle: Cycle): Long = incomes.filter { it.inCycle(cycle) }.sumOf { it.amountMinor }
    /** What can be spent in [cycle]: salary and one-off income, less fixed spends, EMIs and the savings target. */
    fun spendableFor(cycle: Cycle): Long = incomeMinor + extraFor(cycle) - fixedFor(cycle) - savingsMinor
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
    val savingsMinor: Long = 0L,
    val extraIncomeMinor: Long = 0L,
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
            val fixedNow = p.fixedFor(cycle)
            val spendable = p.spendableFor(cycle)
            val left = spendable - spent
            val daysLeft = if (today.isAfter(cycle.end)) 0 else if (today.isBefore(cycle.start)) cycle.days else (ChronoUnit.DAYS.between(today, cycle.end).toInt() + 1)
            val projectedTotal = if (elapsed == 0) 0L else spent * cycle.days / elapsed
            val used = if (spendable <= 0L) (if (spent > 0) 100 else 0) else Math.min(999L, spent * 100 / spendable).toInt()
            BudgetFigures(fixedNow, spendable, left, daysLeft, if (daysLeft > 0 && left > 0) left / daysLeft else 0L, spendable - projectedTotal, used, p.savingsMinor, p.extraFor(cycle))
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
