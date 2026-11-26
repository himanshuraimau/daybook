package com.daybook.mobile.domain

import com.daybook.mobile.data.LocalExpense
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class QuickPicksAndAlertsTest {
    private val today = LocalDate.parse("2026-11-23")
    private fun exp(daysAgo: Long, paise: Long, category: String = "Food", note: String = "") =
        LocalExpense("c$daysAgo-$paise-$note", "o", null, paise, category, today.minusDays(daysAgo).toString(), note, 1, "synced")
    private val profile = BudgetProfile(5_000_000, 27, emptyList(), 1L)

    // ---- quick picks ----
    @Test fun noHistoryMeansNoPicks() { assertTrue(QuickPicks.from(emptyList(), today).isEmpty()) }

    @Test fun firstPickRepeatsTheMostRecentExpense() {
        val picks = QuickPicks.from(listOf(exp(0, 18_000, "Food", "Lunch"), exp(1, 2_000, "Food", "Chai")), today)
        assertTrue(picks[0].isRepeatOfLast); assertEquals(18_000L, picks[0].amountMinor); assertEquals("Lunch", picks[0].note)
        assertEquals(1, picks.size)                                        // Chai happened once: not "frequent"
    }

    @Test fun frequentAmountsFollowTheRepeatOrderedByCountThenRecency() {
        val list = listOf(exp(0, 18_000, "Food", "Lunch"),
            exp(1, 2_000, "Food", "Chai"), exp(2, 2_000, "Food", "chai "), exp(3, 2_000, "Food", "Chai"),     // 3 × (case and spaces ignored)
            exp(4, 15_000, "Transport", "Auto"), exp(5, 15_000, "Transport", "Auto"),                        // 2 ×
            exp(6, 9_900, "Shopping", "Cable"))                                                              // once
        val picks = QuickPicks.from(list, today)
        assertEquals(listOf("Lunch", "Chai", "Auto"), picks.map { it.note.trim().replaceFirstChar { c -> c.uppercase() } })
        assertEquals(3, picks[1].count); assertEquals(2, picks[2].count); assertFalse(picks[1].isRepeatOfLast)
    }

    @Test fun theLastExpenseIsNotListedTwice() {
        val picks = QuickPicks.from(listOf(exp(0, 2_000, "Food", "Chai"), exp(1, 2_000, "Food", "Chai"), exp(2, 2_000, "Food", "Chai")), today)
        assertEquals(1, picks.size); assertTrue(picks[0].isRepeatOfLast)
    }

    @Test fun differentAmountsAreDifferentPicksAndOldHistoryIsIgnored() {
        val picks = QuickPicks.from(listOf(exp(0, 100, "Other"), exp(130, 5_000, "Food", "Old"), exp(131, 5_000, "Food", "Old")), today)
        assertEquals(1, picks.size)
    }

    @Test fun picksAreCapped() {
        val list = (1..10L).flatMap { i -> listOf(exp(i, i * 1_000, "Food", "n$i"), exp(i + 20, i * 1_000, "Food", "n$i")) }
        assertEquals(5, QuickPicks.from(list, today).size)
        assertEquals(3, QuickPicks.from(list, today, 3).size)
    }

    // ---- savings and one-off income ----
    @Test fun savingsTargetLowersSpendableAndOneOffIncomeRaisesItInItsCycle() {
        val cycle = Cycles.containing(today, 27)                           // 27 Oct - 26 Nov
        val bonus = OneOffIncome("b", "Bonus", 1_000_000, "2026-11-10")
        val oldBonus = OneOffIncome("o", "Old", 9_000_000, "2026-10-20")   // belongs to the previous cycle
        val p = BudgetProfile(5_000_000, 27, listOf(FixedSpend("r", "Rent", 1_000_000, "Bills")), 1L, 500_000, listOf(bonus, oldBonus))
        assertEquals(1_000_000L, p.extraFor(cycle))
        assertEquals(5_000_000L + 1_000_000L - 1_000_000L - 500_000L, p.spendableFor(cycle))
        assertEquals(4_000_000L - 500_000L, p.spendableMinor)               // plain figure ignores one-off income
        val b = Insights.summarize(emptyList(), p, today, cycle).budget!!
        assertEquals(4_500_000L, b.spendableMinor); assertEquals(500_000L, b.savingsMinor); assertEquals(1_000_000L, b.extraIncomeMinor)
        val previous = Cycles.previous(cycle, 27)
        assertEquals(9_000_000L, p.extraFor(previous))
    }

    @Test fun incomeOnCycleBoundariesBelongsToTheRightCycle() {
        val p = BudgetProfile(0, 27, emptyList(), 1L, 0, listOf(OneOffIncome("a", "A", 100, "2026-10-27"), OneOffIncome("b", "B", 200, "2026-11-26"), OneOffIncome("c", "C", 400, "2026-11-27")))
        assertEquals(300L, p.extraFor(Cycles.containing(today, 27)))
        assertEquals(400L, p.extraFor(Cycles.next(Cycles.containing(today, 27), 27)))
    }

    // ---- budget alerts ----
    private fun level(spent: Long, elapsedDay: Long, budgetPaise: Long = 4_000_000): AlertLevel {
        val cycle = Cycles.containing(LocalDate.parse("2026-12-30"), 27)       // 27 Dec - 26 Jan (31 days)
        val day = cycle.start.plusDays(elapsedDay - 1)
        val s = Insights.summarize(listOf(LocalExpense("x", "o", null, spent, "Food", day.toString(), "", 1, "synced")), BudgetProfile(budgetPaise, 27, emptyList(), 1L), day, cycle)
        return BudgetAlerts.levelFor(s.budget!!, s.elapsedDays)
    }

    @Test fun quietWhileSpendingIsComfortable() { assertEquals(AlertLevel.NONE, level(spent = 400_000, elapsedDay = 15)) }
    @Test fun nearlyAtEightyPercent() { assertEquals(AlertLevel.NEARLY, level(3_200_000, 20)); assertEquals(AlertLevel.NONE, level(3_100_000, 31)) }
    @Test fun overOnceSpendingPassesTheBudget() { assertEquals(AlertLevel.OVER, level(4_000_001, 25)) }
    @Test fun aheadOfPaceNeedsAWeekOfData() {
        assertEquals(AlertLevel.NONE, level(2_000_000, 3))                  // 50% in 3 days would project over budget, but too early to say
        assertEquals(AlertLevel.AHEAD, level(2_000_000, 8))                 // 50% in 8 days projects ~₹7.7L: ahead of pace
    }
    @Test fun higherRanksOutrankLowerOnes() {
        assertTrue(AlertLevel.OVER.rank > AlertLevel.NEARLY.rank && AlertLevel.NEARLY.rank > AlertLevel.AHEAD.rank && AlertLevel.AHEAD.rank > AlertLevel.NONE.rank)
    }
    @Test fun aBudgetWithNothingSpendableDoesNotCryWolfUntilSomethingIsSpent() {
        val cycle = Cycles.containing(today, 27)
        val tight = BudgetProfile(500_000, 27, listOf(FixedSpend("r", "Rent", 500_000, "Bills")), 1L)
        assertEquals(AlertLevel.NONE, BudgetAlerts.levelFor(Insights.summarize(emptyList(), tight, today, cycle).budget!!, 20))
        assertEquals(AlertLevel.OVER, BudgetAlerts.levelFor(Insights.summarize(listOf(exp(0, 100)), tight, today, cycle).budget!!, 20))
    }
}
