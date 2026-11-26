package com.daybook.mobile.reminder

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.daybook.mobile.MainActivity
import com.daybook.mobile.R
import com.daybook.mobile.data.BudgetStore
import com.daybook.mobile.data.ExpenseStore
import com.daybook.mobile.data.SessionVault
import com.daybook.mobile.domain.AlertLevel
import com.daybook.mobile.domain.BudgetAlerts
import com.daybook.mobile.domain.Cycles
import com.daybook.mobile.domain.Insights
import java.time.LocalDate

/** Local budget warnings. Off until switched on; each level is announced once per cycle and never shows an amount. */
object BudgetAlertNotifier {
    private const val PREFS = "budget_alerts"
    private const val CHANNEL = "budget_alerts"

    fun enabled(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("enabled", false)
    fun set(context: Context, on: Boolean) { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("enabled", on).apply() }

    /** Compares the current cycle with what was already announced and posts at most one notification. */
    @Synchronized fun check(context: Context, today: LocalDate = LocalDate.now()) {
        if (!enabled(context)) return
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val session = try { SessionVault(context).get() } catch (_: Exception) { null } ?: return
        val profile = BudgetStore(context).get(session.ownerId) ?: return
        val store = ExpenseStore(context)
        val all = try { store.all(session.ownerId) } finally { store.close() }
        val cycle = Cycles.containing(today, profile.payday)
        val summary = Insights.summarize(all, profile, today, cycle)
        val level = BudgetAlerts.levelFor(summary.budget ?: return, summary.elapsedDays)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = "${session.ownerId}|${cycle.start}"
        if (level.rank <= prefs.getInt(key, 0)) return
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith("${session.ownerId}|") && it != key }.forEach { editor.remove(it) }
        editor.putInt(key, level.rank).apply()
        post(context, level, profile.payday)
    }

    private fun post(context: Context, level: AlertLevel, payday: Int) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Budget alerts", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(context, 74, Intent(context, MainActivity::class.java).apply {
            putExtra("open_screen", "insights"); flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val text = context.getString(when (level) { AlertLevel.OVER -> R.string.alert_over; AlertLevel.NEARLY -> R.string.alert_nearly; else -> R.string.alert_ahead })
            .let { if (payday == 1) it.replace("cycle", "month") else it }
        manager.notify(73, Notification.Builder(context, CHANNEL).setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle(context.getString(R.string.alert_title)).setContentText(text).setContentIntent(open).setAutoCancel(true).build())
    }
}
