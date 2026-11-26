package com.daybook.mobile.domain

/** How worried the budget should be. Higher ranks replace lower ones within a cycle; each is announced once. */
enum class AlertLevel(val rank: Int) { NONE(0), AHEAD(1), NEARLY(2), OVER(3) }

object BudgetAlerts {
    const val NEARLY_PERCENT = 80
    const val MIN_DAYS_FOR_PACE = 7

    fun levelFor(figures: BudgetFigures, elapsedDays: Int): AlertLevel = when {
        figures.over -> AlertLevel.OVER
        figures.spendableMinor > 0 && figures.usedPercent >= NEARLY_PERCENT -> AlertLevel.NEARLY
        figures.spendableMinor > 0 && elapsedDays >= MIN_DAYS_FOR_PACE && figures.projectedFinishMinor < 0 -> AlertLevel.AHEAD
        else -> AlertLevel.NONE
    }
}
