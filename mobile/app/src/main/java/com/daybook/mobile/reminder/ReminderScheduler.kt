package com.daybook.mobile.reminder

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import java.time.LocalDateTime
import java.time.ZoneId

object ReminderScheduler {
    private const val PREFS = "reminder"
    private const val CHANNEL = "daily_entry"
    fun enabled(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("enabled", false)
    fun hour(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("hour", 22)
    fun minute(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("minute", 0)
    fun set(context: Context, enabled: Boolean, hour: Int = hour(context), minute: Int = minute(context)) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("enabled", enabled).putInt("hour", hour).putInt("minute", minute).apply()
        schedule(context)
    }
    fun schedule(context: Context) {
        val alarm = context.getSystemService(AlarmManager::class.java)
        val intent = PendingIntent.getBroadcast(context, 71, Intent(context, ReminderReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        alarm.cancel(intent)
        if (!enabled(context)) return
        val now = LocalDateTime.now()
        var next = now.toLocalDate().atTime(hour(context), minute(context))
        if (!next.isAfter(now)) next = next.plusDays(1)
        val millis = next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, intent)
    }
    fun show(context: Context) {
        if (!enabled(context)) return
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Daily reminder", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(context, 72, Intent(context, com.daybook.mobile.MainActivity::class.java).apply {
            putExtra("open_add", true)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify(71, android.app.Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentTitle(context.getString(com.daybook.mobile.R.string.reminder_title))
            .setContentText(context.getString(com.daybook.mobile.R.string.reminder_text))
            .setContentIntent(open).setAutoCancel(true).build())
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ReminderScheduler.show(context)
        ReminderScheduler.schedule(context)
    }
}

class ReminderRescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_TIME_CHANGED)) ReminderScheduler.schedule(context)
    }
}
