package com.daybook.mobile

import android.content.Context
import com.daybook.mobile.reminder.BudgetAlertNotifier
import com.daybook.mobile.widget.DaybookWidget

/** Things that should react whenever expenses or the budget change: the widget and budget alerts. */
object AppSignals {
    fun dataChanged(context: Context) {
        try { DaybookWidget.refresh(context) } catch (_: Exception) {}
        try { BudgetAlertNotifier.check(context) } catch (_: Exception) {}
    }
}
