package com.daybook.mobile.ui

import android.Manifest
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.widget.Switch
import android.widget.TextView
import com.daybook.mobile.R
import com.daybook.mobile.data.ExpenseSync
import com.daybook.mobile.reminder.ReminderScheduler
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

class SettingsScreen(private val h: Host) {
    fun show() {
        val v = h.inflate(R.layout.screen_settings, "settings")
        val ctx = h.context
        val owner = h.account.ownerId

        val profile = h.budgets.get(owner)
        val title = v.findViewById<TextView>(R.id.settings_budget_title)
        val sub = v.findViewById<TextView>(R.id.settings_budget_sub)
        if (profile == null) { title.text = "Set up your budget"; sub.text = "Salary, payday and fixed spends" }
        else {
            title.text = "Salary ${Fmt.money(profile.incomeMinor)} · paid on the ${Fmt.ordinal(profile.payday)}"
            sub.text = "Fixed ${Fmt.money(profile.fixedMinor)} · Spendable ${Fmt.money(profile.spendableMinor)}" + if (h.budgets.dirty(owner)) "\nSaved on this phone, waiting to sync" else ""
        }
        v.findViewById<android.view.View>(R.id.settings_budget_card).setOnClickListener { h.showBudget(false) }

        val toggle = v.findViewById<Switch>(R.id.reminder_switch)
        val time = v.findViewById<TextView>(R.id.reminder_time)
        val help = v.findViewById<TextView>(R.id.reminder_help)
        fun updateTime() { time.text = "Time    ${LocalTime.of(ReminderScheduler.hour(ctx), ReminderScheduler.minute(ctx)).format(DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH))}" }
        updateTime()
        toggle.isChecked = ReminderScheduler.enabled(ctx)
        toggle.setOnCheckedChangeListener { _, checked ->
            if (checked && Build.VERSION.SDK_INT >= 33 && ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                toggle.isChecked = false; h.requestNotificationPermission()
            } else ReminderScheduler.set(ctx, checked)
        }
        time.setOnClickListener { TimePickerDialog(ctx, { _, hour, minute -> ReminderScheduler.set(ctx, toggle.isChecked, hour, minute); updateTime() }, ReminderScheduler.hour(ctx), ReminderScheduler.minute(ctx), false).show() }
        if (Build.VERSION.SDK_INT >= 33 && ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            help.text = "Notifications are off. Tap to open system settings."
            help.setOnClickListener { ctx.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)) }
        }

        v.findViewById<TextView>(R.id.account_email).text = h.account.email
        val waiting = h.store.unsyncedCount(owner)
        v.findViewById<TextView>(R.id.account_sync).text = if (waiting == 0) "All synced" else "$waiting expense${if (waiting == 1) "" else "s"} waiting to sync · Retry"
        v.findViewById<TextView>(R.id.account_sync).setOnClickListener {
            if (waiting > 0) { h.store.retryFailed(owner); ExpenseSync.schedule(ctx); h.refreshFromServer() }
        }
        v.findViewById<android.view.View>(R.id.sign_out).setOnClickListener {
            AlertDialog.Builder(ctx, R.style.DaybookDialog).setTitle("Sign out?")
                .setMessage(if (waiting > 0) "$waiting unsynced expense${if (waiting == 1) " is" else "s are"} saved on this phone for this account. Sign in to this account again to sync." else "You can sign in again later.")
                .setNegativeButton("Cancel", null).setPositiveButton("Sign out") { _, _ -> h.signOut() }.show()
        }
    }
}
