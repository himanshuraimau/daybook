package com.daybook.mobile.domain

import com.daybook.mobile.data.LocalExpense
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class BudgetTest {
    private fun d(s: String) = LocalDate.parse(s)
    private fun exp(date: String, paise: Long, category: String = "Food", note: String = "") =
        LocalExpense("c-$date-$paise", "o", null, paise, category, date, note, 1, "synced")
    private val profile = BudgetProfile(5_000_000, 27, listOf(FixedSpend("r", "Rent", 1_000_000, "Bills")), 1L)

    @Test fun paydayFallsOnLastDayOfShortMonths() {
        assertEquals(d("2026-11-27"), Cycles.payDate(YearMonth.of(2026, 11), 27))
        assertEquals(d("2027-02-28"), Cycles.payDate(YearMonth.of(2027, 2), 31))
        assertEquals(d("2028-02-29"), Cycles.payDate(YearMonth.of(2028, 2), 31))
        assertEquals(d("2026-04-30"), Cycles.payDate(YearMonth.of(2026, 4), 31))
    }

    @Test fun cycleStartsOnPaydayAndEndsTheDayBeforeTheNext() {
        assertEquals(Cycle(d("2026-10-27"), d("2026-11-26")), Cycles.containing(d("2026-11-26"), 27))
        assertEquals(Cycle(d("2026-11-27"), d("2026-12-26")), Cycles.containing(d("2026-11-27"), 27))
        assertEquals(Cycle(d("2026-12-27"), d("2027-01-26")), Cycles.containing(d("2027-01-10"), 27))
        assertEquals(Cycle(d("2026-11-27"), d("2026-12-26")), Cycles.containing(d("2026-12-26"), 27))
        assertEquals(30, Cycles.containing(d("2026-11-30"), 27).days)
    }

    @Test fun paydayOneIsACalendarMonth() {
        assertEquals(Cycle(d("2026-11-01"), d("2026-11-30")), Cycles.containing(d("2026-11-23"), 1))
        assertEquals(Cycle(d("2028-02-01"), d("2028-02-29")), Cycles.containing(d("2028-02-10"), 1))
    }

    @Test fun payday31UsesTheLastDayInShortMonths() {
        assertEquals(Cycle(d("2027-01-31"), d("2027-02-27")), Cycles.containing(d("2027-02-15"), 31))
        assertEquals(Cycle(d("2027-02-28"), d("2027-03-30")), Cycles.containing(d("2027-02-28"), 31))
    }

    @Test fun cyclesAreContiguousAndNeverOverlapForEveryPayday() {
        for (payday in listOf(1, 15, 27, 29, 30, 31)) {
            var date = d("2026-01-01")
            while (date.isBefore(d("2028-12-31"))) {
                val cycle = Cycles.containing(date, payday)
                assertTrue("$payday $date", date in cycle)
                assertEquals("$payday $date", cycle.end.plusDays(1), Cycles.next(cycle, payday).start)
                assertEquals("$payday $date", cycle.start.minusDays(1), Cycles.previous(cycle, payday).end)
                assertEquals(cycle, Cycles.containing(cycle.start, payday))
                assertEquals(cycle, Cycles.containing(cycle.end, payday))
                date = date.plusDays(7)
            }
        }
    }

    @Test fun cycleListsTheCalendarMonthsItTouches() {
        assertEquals(listOf(YearMonth.of(2026, 10), YearMonth.of(2026, 11)), Cycles.containing(d("2026-11-10"), 27).months())
        assertEquals(listOf(YearMonth.of(2026, 11)), Cycles.containing(d("2026-11-10"), 1).months())
    }

    @Test fun leftToSpendAllowanceAndProjection() {
        val cycle = Cycles.containing(d("2026-12-06"), 27) // 27 Nov - 26 Dec, ten days elapsed
        val expenses = listOf(exp("2026-11-27", 200_000), exp("2026-12-01", 250_000, "Bills"), exp("2026-12-06", 150_000, "Food", "Lunch"))
        val s = Insights.summarize(expenses, profile, d("2026-12-06"), cycle)
        val b = s.budget!!
        assertEquals(600_000L, s.spentMinor)
        assertEquals(150_000L, s.todayMinor)
        assertEquals(4_000_000L, b.spendableMinor)
        assertEquals(3_400_000L, b.leftMinor)
        assertEquals(21, b.daysLeft)
        assertEquals(3_400_000L / 21, b.dailyAllowanceMinor)
        assertEquals(10, s.elapsedDays)
        assertEquals(7, s.noSpendDays)
        assertEquals(60_000L, s.averagePerDayMinor)
        assertEquals(4_000_000L - 1_800_000L, b.projectedFinishMinor)
        assertEquals(15, b.usedPercent)
        assertEquals(250_000L, s.biggest!!.amountMinor)
        assertEquals(listOf("Food", "Bills"), s.categories.map { it.category })
        assertEquals(350_000L, s.categories[0].amountMinor)
    }

    @Test fun overBudgetIsNegativeAndAllowanceIsZero() {
        val cycle = Cycles.containing(d("2026-12-06"), 27)
        val s = Insights.summarize(listOf(exp("2026-12-01", 4_230_000)), profile, d("2026-12-06"), cycle)
        val b = s.budget!!
        assertEquals(-230_000L, b.leftMinor)
        assertTrue(b.over)
        assertEquals(0L, b.dailyAllowanceMinor)
        assertEquals(105, b.usedPercent)
        assertTrue(b.projectedFinishMinor < 0)
    }

    @Test fun fixedSpendsLargerThanSalaryShowNegativeSpendable() {
        val tight = BudgetProfile(900_000, 27, listOf(FixedSpend("r", "Rent", 1_000_000, "Bills")), 1L)
        assertEquals(-100_000L, tight.spendableMinor)
        val s = Insights.summarize(listOf(exp("2026-12-01", 1_000)), tight, d("2026-12-06"), Cycles.containing(d("2026-12-06"), 27))
        assertEquals(-101_000L, s.budget!!.leftMinor)
        assertEquals(100, s.budget!!.usedPercent)
        assertEquals(0L, s.budget!!.dailyAllowanceMinor)
    }

    @Test fun lastDayOfCycleAllowanceCoversOnlyToday() {
        val cycle = Cycles.containing(d("2026-12-26"), 27)
        val b = Insights.summarize(listOf(exp("2026-12-01", 1_000_000)), profile, d("2026-12-26"), cycle).budget!!
        assertEquals(1, b.daysLeft)
        assertEquals(3_000_000L, b.dailyAllowanceMinor)
    }

    @Test fun pastCycleHasNoDaysLeftAndFullElapsed() {
        val past = Cycles.containing(d("2026-10-30"), 27)
        val s = Insights.summarize(listOf(exp("2026-10-30", 100_000)), profile, d("2026-12-06"), past)
        assertEquals(past.days, s.elapsedDays)
        assertEquals(0, s.budget!!.daysLeft)
        assertEquals(0L, s.budget!!.dailyAllowanceMinor)
        assertEquals(0L, s.todayMinor)
    }

    @Test fun withoutABudgetOnlyPlainTotalsAreProduced() {
        val s = Insights.summarize(listOf(exp("2026-12-01", 100_000)), null, d("2026-12-06"), Cycles.containing(d("2026-12-06"), 1))
        assertNull(s.budget)
        assertEquals(100_000L, s.spentMinor)
    }

    @Test fun expensesOutsideTheCycleAreIgnored() {
        val cycle = Cycles.containing(d("2026-12-06"), 27)
        val s = Insights.summarize(listOf(exp("2026-11-26", 999_999), exp("2026-12-27", 999_999), exp("2026-12-02", 100)), profile, d("2026-12-06"), cycle)
        assertEquals(100L, s.spentMinor)
    }

    @Test fun comparesWithThePreviousCycleUpToTheSameDay() {
        val cycle = Cycles.containing(d("2026-12-06"), 27)
        val prev = Cycles.previous(cycle, 27) // 27 Oct - 26 Nov
        val expenses = listOf(exp("2026-10-28", 400_000), exp("2026-11-05", 999_999) /* day 10 of prev; still counted */, exp("2026-11-20", 700_000) /* after day 10 */, exp("2026-11-30", 300_000))
        val s = Insights.summarize(expenses + exp("2026-12-02", 700_000), profile, d("2026-12-06"), cycle, prev)
        // prev cycle days 1-10 = 27 Oct..5 Nov: 400,000 + 999,999. Current spent = 700,000 + 300,000 (30 Nov).
        assertEquals(Math.round((1_000_000 - 1_399_999) * 100.0 / 1_399_999).toInt(), s.vsPreviousPercent)
        val none = Insights.summarize(listOf(exp("2026-12-02", 700_000)), profile, d("2026-12-06"), cycle, prev)
        assertNull(none.vsPreviousPercent)
    }

    @Test fun streakCountsBackFromTodayOrYesterday() {
        val today = d("2026-11-23")
        val logged = setOf(d("2026-11-23"), d("2026-11-22"), d("2026-11-21"), d("2026-11-19"))
        assertEquals(3, Streak.current(logged, today))
        assertEquals(2, Streak.current(setOf(d("2026-11-22"), d("2026-11-21")), today))
        assertEquals(0, Streak.current(setOf(d("2026-11-20")), today))
        assertEquals(0, Streak.current(emptySet(), today))
        val week = Streak.week(logged, today)
        assertEquals(7, week.size)
        assertEquals(d("2026-11-17"), week.first().first)
        assertEquals(listOf(false, false, true, false, true, true, true), week.map { it.second })
        assertFalse(week[1].second)
    }

    private fun emi(months: Int, startKey: String, paise: Long = 500_000) = FixedSpend("emi", "Phone", paise, "Shopping", months, startKey)

    @Test fun emiIsChargedOnlyInItsMonthsStartingFromTheStartCycle() {
        val e = emi(3, "2026-11")                                       // payday 27: 27 Nov - 26 Dec is instalment 1
        fun idx(day: String) = e.instalmentIn(Cycles.containing(d(day), 27))
        assertEquals(0, idx("2026-10-30")); assertFalse(e.chargedIn(Cycles.containing(d("2026-10-30"), 27)))
        assertEquals(1, idx("2026-11-27")); assertEquals(1, idx("2026-12-26"))
        assertEquals(2, idx("2026-12-27")); assertEquals(3, idx("2027-02-26"))
        assertTrue(e.chargedIn(Cycles.containing(d("2027-02-26"), 27)))
        assertEquals(4, idx("2027-02-27")); assertFalse(e.chargedIn(Cycles.containing(d("2027-02-27"), 27)))
        assertEquals(1_500_000L, e.totalMinor)
    }

    @Test fun emiWorksWithCalendarMonthsAndMonthEndPaydays() {
        val e = emi(12, "2026-11")
        assertEquals(1, e.instalmentIn(Cycles.containing(d("2026-11-23"), 1)))
        assertEquals(12, e.instalmentIn(Cycles.containing(d("2027-10-05"), 1)))
        assertFalse(e.chargedIn(Cycles.containing(d("2027-11-05"), 1)))
        // payday 31: cycles start on the last day of short months but still advance one month at a time
        var cycle = Cycles.containing(d("2026-11-30"), 31)
        val seen = ArrayList<Int>()
        repeat(14) { seen.add(e.instalmentIn(cycle)); cycle = Cycles.next(cycle, 31) }
        assertEquals((1..14).toList(), seen)
    }

    @Test fun emiStartedLaterOrAlreadyPartlyPaid() {
        val next = emi(6, "2026-12")                                  // starts with the next cycle
        assertFalse(next.chargedIn(Cycles.containing(d("2026-11-23"), 1)))
        assertTrue(next.chargedIn(Cycles.containing(d("2026-12-23"), 1)))
        val partlyPaid = emi(12, "2026-08")                           // four instalments already paid before November
        assertEquals(4, partlyPaid.instalmentIn(Cycles.containing(d("2026-11-23"), 1)))
    }

    @Test fun plainFixedSpendsAreAlwaysCharged() {
        val rent = FixedSpend("r", "Rent", 1_000_000, "Bills")
        assertFalse(rent.isEmi)
        assertTrue(rent.chargedIn(Cycles.containing(d("2030-01-01"), 27)))
    }

    @Test fun spendableRisesWhenAnEmiEnds() {
        val rent = FixedSpend("r", "Rent", 1_000_000, "Bills")
        val p = BudgetProfile(5_000_000, 27, listOf(rent, emi(2, "2026-10")), 1L)
        val oct = Cycles.containing(d("2026-10-30"), 27); val nov = Cycles.containing(d("2026-11-30"), 27); val dec = Cycles.containing(d("2026-12-30"), 27)
        assertEquals(1_500_000L, p.fixedFor(oct)); assertEquals(3_500_000L, p.spendableFor(oct))
        assertEquals(1_500_000L, p.fixedFor(nov))
        assertEquals(1_000_000L, p.fixedFor(dec)); assertEquals(4_000_000L, p.spendableFor(dec))
        assertEquals(1_500_000L, p.fixedMinor)                         // the plain total ignores schedules
    }

    @Test fun summaryUsesTheFixedSpendsOfThatCycle() {
        val p = BudgetProfile(5_000_000, 27, listOf(FixedSpend("r", "Rent", 1_000_000, "Bills"), emi(2, "2026-10")), 1L)
        val last = Cycles.containing(d("2026-12-30"), 27)               // 27 Dec - 26 Jan: instalment 3 of 2, finished
        val b = Insights.summarize(listOf(exp("2026-12-28", 100_000)), p, d("2026-12-30"), last).budget!!
        assertEquals(1_000_000L, b.fixedMinor); assertEquals(4_000_000L, b.spendableMinor); assertEquals(3_900_000L, b.leftMinor)
        val before = Cycles.previous(last, 27)                          // 27 Nov - 26 Dec: instalment 2 of 2, still charged
        val b2 = Insights.summarize(emptyList(), p, d("2026-12-10"), before).budget!!
        assertEquals(1_500_000L, b2.fixedMinor); assertEquals(3_500_000L, b2.leftMinor)
    }
}
