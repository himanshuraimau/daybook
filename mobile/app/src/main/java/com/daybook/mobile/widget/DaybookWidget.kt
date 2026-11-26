package com.daybook.mobile.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.daybook.mobile.MainActivity
import com.daybook.mobile.R
import com.daybook.mobile.data.BudgetStore
import com.daybook.mobile.data.ExpenseStore
import com.daybook.mobile.data.SessionVault
import com.daybook.mobile.domain.Cycles
import com.daybook.mobile.domain.Insights
import com.daybook.mobile.ui.Fmt
import java.time.LocalDate

/** What the home-screen widget shows. Built from the same local data as the Home screen. */
object WidgetContent {
    data class Content(val label: String, val amount: String, val sub: String)

    fun build(context: Context, today: LocalDate = LocalDate.now()): Content = try {
        val session = SessionVault(context).get()
        if (session == null) Content("DAYBOOK", "Sign in", "Open the app to start")
        else {
            val store = ExpenseStore(context)
            val all = try { store.all(session.ownerId) } finally { store.close() }
            val profile = BudgetStore(context).get(session.ownerId)
            val cycle = Cycles.containing(today, profile?.payday ?: 1)
            val s = Insights.summarize(all, profile, today, cycle)
            val b = s.budget
            when {
                b == null -> Content("SPENT TODAY", Fmt.money(s.todayMinor), "This ${if ((profile?.payday ?: 1) == 1) "month" else "cycle"} ${Fmt.money(s.spentMinor)}")
                b.over -> Content("OVER BUDGET", "Over by ${Fmt.money(-b.leftMinor)}", "Today ${Fmt.money(s.todayMinor)}")
                b.daysLeft > 0 -> Content("LEFT TO SPEND", Fmt.money(b.leftMinor), "${Fmt.money(b.dailyAllowanceMinor)} a day · ${b.daysLeft} ${if (b.daysLeft == 1) "day" else "days"} left")
                else -> Content("LEFT TO SPEND", Fmt.money(b.leftMinor), "This cycle has ended")
            }
        }
    } catch (_: Exception) { Content("DAYBOOK", "Open the app", "") }
}

class DaybookWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) manager.updateAppWidget(id, views(context))
    }

    companion object {
        fun views(context: Context): RemoteViews {
            val c = WidgetContent.build(context)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val open = PendingIntent.getActivity(context, 81, Intent(context, MainActivity::class.java).apply { addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP) }, flags)
            val add = PendingIntent.getActivity(context, 82, Intent(context, MainActivity::class.java).apply { putExtra("open_add", true); addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP) }, flags)
            return RemoteViews(context.packageName, R.layout.widget_daybook).apply {
                setTextViewText(R.id.widget_label, c.label); setTextViewText(R.id.widget_amount, c.amount); setTextViewText(R.id.widget_sub, c.sub)
                setOnClickPendingIntent(R.id.widget_root, open)
                setOnClickPendingIntent(R.id.widget_add, add)
            }
        }

        /** Redraws every placed widget; call after the data it shows changes. */
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, DaybookWidget::class.java))
            if (ids.isNotEmpty()) for (id in ids) manager.updateAppWidget(id, views(context))
        }
    }
}
