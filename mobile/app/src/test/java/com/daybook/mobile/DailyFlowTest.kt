package com.daybook.mobile

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import com.daybook.mobile.data.AccountSession
import com.daybook.mobile.data.ExpenseStore
import com.daybook.mobile.data.SessionVault
import com.daybook.mobile.reminder.ReminderScheduler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** UI-level checks of the daily flow on the JVM. They do not replace a real-device pass. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DailyFlowTest {
    private val app: Application get() = org.robolectric.RuntimeEnvironment.getApplication()
    private val owner = "owner-1"
    private val today = LocalDate.now().toString()

    @Before fun signIn() {
        FakeAndroidKeyStore.install()
        app.deleteDatabase("daybook.db")
        SessionVault(app).save(AccountSession(owner, "me@example.com", "token"))
        ReminderScheduler.set(app, false)
    }
    @After fun cleanUp() { app.deleteDatabase("daybook.db"); SessionVault(app).clear() }

    private fun store() = ExpenseStore(app)
    private fun text(activity: MainActivity, id: Int) = activity.findViewById<TextView>(id).text.toString()
    private fun homeRecent(activity: MainActivity) = activity.findViewById<LinearLayout>(R.id.recent)
    private fun allText(view: View): String =
        if (view is android.view.ViewGroup) (0 until view.childCount).joinToString("\n") { allText(view.getChildAt(it)) }
        else (view as? TextView)?.text?.toString().orEmpty()

    @Test fun openingPromptShowsOncePerOpeningAndCanBeDismissed() {
        val first = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = first.get()
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.open_prompt).visibility)

        activity.findViewById<View>(R.id.prompt_dismiss).performClick()
        assertEquals(View.GONE, activity.findViewById<View>(R.id.open_prompt).visibility)

        // Navigating within one opening does not bring it back.
        activity.findViewById<View>(R.id.nav_history).performClick()
        activity.findViewById<View>(R.id.nav_home).performClick()
        assertEquals(View.GONE, activity.findViewById<View>(R.id.open_prompt).visibility)

        // Coming back from the background is a new opening.
        first.pause().stop().start().resume()
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.open_prompt).visibility)
        first.pause().stop().destroy()
    }

    @Test fun quickAddFromPromptOpensEntryWithTodayPreselected() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        activity.findViewById<View>(R.id.prompt_add).performClick()
        assertEquals("Add expense", text(activity, R.id.entry_title))
        assertEquals(today, text(activity, R.id.entry_date))
        val group = activity.findViewById<RadioGroup>(R.id.entry_categories)
        assertEquals(ExpenseStore.CATEGORIES.size, group.childCount)
        controller.pause().stop().destroy()
    }

    @Test fun invalidEntryIsRejectedAndNothingIsStored() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        activity.findViewById<View>(R.id.home_add).performClick()
        val amount = activity.findViewById<EditText>(R.id.entry_amount)
        for (bad in listOf("", "0", "-5", "abc", "12.345")) {
            amount.setText(bad)
            activity.findViewById<View>(R.id.entry_save).performClick()
            assertTrue("'$bad' should be rejected", text(activity, R.id.entry_error).contains("positive amount"))
        }
        controller.pause().stop().destroy()
        store().use { assertEquals(0, it.all(owner).size) }
    }

    @Test fun savedEntryIsDurableAndShownAsPendingInTotals() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        activity.findViewById<View>(R.id.home_add).performClick()
        activity.findViewById<EditText>(R.id.entry_amount).setText("123.45")
        activity.findViewById<EditText>(R.id.entry_note).setText("Lunch")
        activity.findViewById<View>(R.id.entry_save).performClick()

        assertEquals("", activity.findViewById<TextView?>(R.id.entry_error)?.text?.toString().orEmpty())
        assertTrue(text(activity, R.id.today_total).contains("123.45"))
        assertTrue(text(activity, R.id.month_total).contains("123.45"))
        assertTrue(allText(homeRecent(activity)).contains("Pending sync"))
        assertEquals("1 expense waiting to sync · Retry", text(activity, R.id.sync_banner))
        controller.pause().stop().destroy()

        // A fresh process view of the same database still has the entry and its queued operation.
        store().use {
            val saved = it.all(owner).single()
            assertEquals(12345L, saved.amountMinor)
            assertEquals(today, saved.occurredOn)
            assertEquals("pending", saved.status)
            assertEquals(1, it.pending(owner).size)
        }
        // Reopen: still visible.
        val again = Robolectric.buildActivity(MainActivity::class.java).setup()
        assertTrue(text(again.get(), R.id.today_total).contains("123.45"))
        again.pause().stop().destroy()
    }

    @Test fun totalsFollowBackdatedEditAndDeleteAndMatchTheList() {
        val old = LocalDate.now().withDayOfMonth(1).let { if (it.toString() == today) it.minusDays(1) else it }.toString()
        val id = store().use { s ->
            s.add(owner, 5000, "Food", today, "Today")
            s.add(owner, 20000, "Bills", old, "Backdated")
        }
        val sameMonth = old.startsWith(today.substring(0, 7))
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        assertTrue(text(activity, R.id.today_total).contains("50"))
        assertFalse(text(activity, R.id.today_total).contains("250"))
        if (sameMonth) assertTrue(text(activity, R.id.month_total).contains("250"))
        else assertFalse(text(activity, R.id.month_total).contains("250"))

        // Edit the backdated pending entry from the Home list to ₹300.
        val row = homeRecent(activity).children().first { allText(it).contains("Backdated") }
        row.performClick()
        assertEquals("Edit expense", text(activity, R.id.entry_title))
        activity.findViewById<EditText>(R.id.entry_amount).setText("300")
        activity.findViewById<View>(R.id.entry_save).performClick()
        if (sameMonth) assertTrue(text(activity, R.id.month_total).contains("350"))
        assertTrue(text(activity, R.id.today_total).contains("50"))

        // Delete it (confirmation dialog) and check the total drops back.
        homeRecent(activity).children().first { allText(it).contains("Backdated") }.performClick()
        activity.findViewById<View>(R.id.entry_delete).performClick()
        val dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
        assertNotNull(dialog)
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertNotNull("still on entry: " + activity.findViewById<TextView?>(R.id.entry_error)?.text, activity.findViewById<View?>(R.id.recent))
        assertFalse(allText(homeRecent(activity)).contains("Backdated"))
        assertTrue(text(activity, R.id.month_total).contains("50"))
        assertFalse(text(activity, R.id.month_total).contains("350"))
        controller.pause().stop().destroy()
        assertNotNull(id)
    }

    @Test fun emptyHomeShowsGuidance() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        assertTrue(allText(activity.findViewById(R.id.categories)).contains("Add your first expense"))
        assertTrue(allText(homeRecent(activity)).contains("Nothing recorded"))
        controller.pause().stop().destroy()
    }

    @Test fun otherAccountsEntriesNeverAppear() {
        store().use { it.add("someone-else", 99900, "Food", today, "Secret") }
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        assertFalse(allText(activity.findViewById(R.id.screen)).contains("999"))
        controller.pause().stop().destroy()
    }

    // ---- reminder ----

    private fun alarmManager() = shadowOf(app.getSystemService(AlarmManager::class.java))
    private fun nextAlarm(): ShadowAlarmManager.ScheduledAlarm? = alarmManager().peekNextScheduledAlarm()

    @Test fun reminderSchedulesAtChosenTimeReschedulesAndCancels() {
        assertNull(nextAlarm())
        ReminderScheduler.set(app, true, 21, 30)
        val alarm = nextAlarm()
        assertNotNull(alarm)
        val fires = java.time.Instant.ofEpochMilli(alarm!!.triggerAtMs).atZone(ZoneId.systemDefault()).toLocalDateTime()
        assertEquals(21, fires.hour); assertEquals(30, fires.minute)
        assertTrue(fires.isAfter(LocalDateTime.now().minusSeconds(1)))
        assertTrue(fires.isBefore(LocalDateTime.now().plusDays(1).plusMinutes(1)))

        ReminderScheduler.set(app, true, 7, 5)
        val moved = java.time.Instant.ofEpochMilli(nextAlarm()!!.triggerAtMs).atZone(ZoneId.systemDefault()).toLocalDateTime()
        assertEquals(7, moved.hour); assertEquals(5, moved.minute)
        assertEquals(1, alarmManager().scheduledAlarms.size)

        // Boot / time-zone broadcasts restore the schedule.
        alarmManager().scheduledAlarms.clear()
        app.sendBroadcast(Intent(Intent.ACTION_BOOT_COMPLETED))
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertNotNull(nextAlarm())

        ReminderScheduler.set(app, false)
        assertNull(nextAlarm())
    }

    @Test fun notificationHasNoAmountAndTapOpensEntry() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        store().use { it.add(owner, 123456, "Food", today, "Dinner") }
        ReminderScheduler.set(app, true, 22, 0)
        ReminderScheduler.show(app)
        val manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val posted = shadowOf(manager).allNotifications.single()
        val body = "${posted.extras.getCharSequence(Notification.EXTRA_TITLE)} ${posted.extras.getCharSequence(Notification.EXTRA_TEXT)}"
        assertFalse(body, body.contains(Regex("[0-9₹]")))

        val launched = shadowOf(posted.contentIntent).savedIntent
        assertTrue(launched.getBooleanExtra("open_add", false))
        val controller = Robolectric.buildActivity(MainActivity::class.java, launched).setup()
        assertEquals("Add expense", text(controller.get(), R.id.entry_title))
        controller.pause().stop().destroy()
    }

    @Test fun reminderIsSilentWithoutNotificationPermission() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        ReminderScheduler.set(app, true, 22, 0)
        ReminderScheduler.show(app)
        val manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }

    @Test fun enablingReminderWithoutPermissionKeepsItOffAndExplains() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        activity.findViewById<View>(R.id.nav_settings).performClick()
        val toggle = activity.findViewById<Switch>(R.id.reminder_switch)
        toggle.isChecked = true
        assertFalse(toggle.isChecked)
        assertFalse(ReminderScheduler.enabled(app))
        assertNull(nextAlarm())
        assertTrue(text(activity, R.id.reminder_help).contains("Notifications are off"))
        controller.pause().stop().destroy()
    }

    @Test fun enablingReminderWithPermissionSchedulesIt() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        activity.findViewById<View>(R.id.nav_settings).performClick()
        activity.findViewById<Switch>(R.id.reminder_switch).isChecked = true
        assertTrue(ReminderScheduler.enabled(app))
        assertNotNull(nextAlarm())
        controller.pause().stop().destroy()
    }

    private fun LinearLayout.children() = (0 until childCount).map { getChildAt(it) }
}
