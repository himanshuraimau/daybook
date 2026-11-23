package com.daybook.mobile

import android.Manifest
import android.app.AlarmManager
import android.app.AlertDialog
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import com.daybook.mobile.data.AccountSession
import com.daybook.mobile.data.BudgetStore
import com.daybook.mobile.data.ExpenseStore
import com.daybook.mobile.data.SessionVault
import com.daybook.mobile.domain.Cycles
import com.daybook.mobile.domain.FixedSpend
import com.daybook.mobile.reminder.ReminderScheduler
import com.daybook.mobile.ui.Fmt
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
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.shadows.ShadowAlertDialog
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** UI-level checks of the daily flow on the JVM. They do not replace a real-device pass. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DailyFlowTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val owner = "owner-1"
    private val today = LocalDate.now()
    private val todayText = today.toString()

    @Before fun signIn() {
        com.daybook.mobile.data.ApiClient.baseUrl = ""          // unit tests never talk to a real server
        FakeAndroidKeyStore.install()
        app.deleteDatabase("daybook.db")
        app.getSharedPreferences("budget", 0).edit().clear().commit()
        SessionVault(app).save(AccountSession(owner, "me@example.com", "token"))
        ReminderScheduler.set(app, false)
    }
    @After fun cleanUp() { app.deleteDatabase("daybook.db"); app.getSharedPreferences("budget", 0).edit().clear().commit(); SessionVault(app).clear() }

    private fun store() = ExpenseStore(app)
    private fun launch(): ActivityController<MainActivity> = Robolectric.buildActivity(MainActivity::class.java).setup()
    private fun text(a: MainActivity, id: Int) = a.findViewById<TextView>(id).text.toString()
    private fun view(a: MainActivity, name: String): View? = a.findViewById(a.resources.getIdentifier(name, "id", a.packageName))
    private fun allText(v: View): String =
        if (v is ViewGroup) (0 until v.childCount).joinToString("\n") { allText(v.getChildAt(it)) } else (v as? TextView)?.text?.toString().orEmpty()
    private fun children(v: ViewGroup) = (0 until v.childCount).map { v.getChildAt(it) }
    private fun idle() = shadowOf(android.os.Looper.getMainLooper()).idle()

    /** Types an amount on the on-screen number pad. */
    private fun type(a: MainActivity, amount: String) {
        for (c in amount) a.findViewById<View>(a.resources.getIdentifier(if (c == '.') "key_dot" else "key_$c", "id", a.packageName)).performClick()
    }
    private fun chip(a: MainActivity, label: String): TextView =
        children(a.findViewById<GridLayout>(R.id.entry_categories)).map { it as TextView }.first { it.text.toString() == label }
    private fun confirmDialog() { ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick(); idle() }

    // ---------- opening prompt and entry ----------

    @Test fun openingPromptShowsOncePerOpeningAndCanBeDismissed() {
        val first = launch(); val a = first.get()
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.open_prompt).visibility)
        assertEquals(View.GONE, a.findViewById<View>(R.id.home_add).visibility)

        a.findViewById<View>(R.id.prompt_dismiss).performClick()
        assertEquals(View.GONE, a.findViewById<View>(R.id.open_prompt).visibility)
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.home_add).visibility)

        a.findViewById<View>(R.id.nav_history).performClick()
        a.findViewById<View>(R.id.nav_home).performClick()
        assertEquals(View.GONE, a.findViewById<View>(R.id.open_prompt).visibility)

        first.pause().stop().start().resume()          // coming back from the background is a new opening
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.open_prompt).visibility)
        first.pause().stop().destroy()
    }

    @Test fun quickAddOpensEntryWithTodayAndFoodPreselected() {
        val c = launch(); val a = c.get()
        a.findViewById<View>(R.id.prompt_add).performClick()
        assertEquals("Add expense", text(a, R.id.entry_title))
        assertTrue(a.findViewById<View>(R.id.entry_date_today).isSelected)
        assertFalse(a.findViewById<View>(R.id.entry_date_yesterday).isSelected)
        assertTrue(chip(a, "Food").isSelected)
        assertEquals(7, a.findViewById<GridLayout>(R.id.entry_categories).childCount)
        assertEquals("₹0", text(a, R.id.entry_amount))
        c.pause().stop().destroy()
    }

    @Test fun invalidEntryIsRejectedAndNothingIsStored() {
        val c = launch(); val a = c.get()
        a.findViewById<View>(R.id.prompt_add).performClick()
        a.findViewById<View>(R.id.entry_save).performClick()                       // nothing typed
        assertTrue(text(a, R.id.entry_error).contains("above ₹0"))
        type(a, "0"); a.findViewById<View>(R.id.entry_save).performClick()
        assertTrue(text(a, R.id.entry_error).contains("above ₹0"))
        c.pause().stop().destroy()
        store().use { assertEquals(0, it.all(owner).size) }
    }

    @Test fun numberPadFollowsMoneyRules() {
        val c = launch(); val a = c.get()
        a.findViewById<View>(R.id.prompt_add).performClick()
        type(a, "007"); assertEquals("₹7", text(a, R.id.entry_amount))              // no leading zeros
        type(a, "1234567"); assertEquals("₹7,12,34,567", text(a, R.id.entry_amount))   // Indian digit grouping
        repeat(8) { a.findViewById<View>(R.id.key_back).performClick() }
        assertEquals("₹0", text(a, R.id.entry_amount))
        type(a, ".5"); assertEquals("₹0.5", text(a, R.id.entry_amount))
        type(a, "55"); assertEquals("₹0.55", text(a, R.id.entry_amount))             // at most two decimals
        type(a, "."); assertEquals("₹0.55", text(a, R.id.entry_amount))              // one decimal point
        c.pause().stop().destroy()
    }

    @Test fun savedEntryIsDurableAndShownAsPendingInTotals() {
        val c = launch(); val a = c.get()
        a.findViewById<View>(R.id.prompt_add).performClick()
        type(a, "123.45")
        a.findViewById<EditText>(R.id.entry_note).setText("Lunch")
        a.findViewById<View>(R.id.entry_save).performClick()

        assertEquals("spent today", text(a, R.id.hero_label).lowercase())               // back on Home
        assertEquals(Fmt.money(12345), text(a, R.id.hero_amount))
        assertTrue(allText(a.findViewById(R.id.recent)).contains("Pending sync"))
        assertEquals("1 waiting to sync · Retry", text(a, R.id.hero_chip2))
        c.pause().stop().destroy()

        store().use {
            val saved = it.all(owner).single()
            assertEquals(12345L, saved.amountMinor); assertEquals(todayText, saved.occurredOn)
            assertEquals("Food", saved.category); assertEquals("pending", saved.status); assertEquals(1, it.pending(owner).size)
        }
        val again = launch()
        assertEquals(Fmt.money(12345), text(again.get(), R.id.hero_amount))
        again.pause().stop().destroy()
    }

    @Test fun yesterdayAndCategoryChipsAreUsedWhenSaving() {
        val c = launch(); val a = c.get()
        a.findViewById<View>(R.id.prompt_add).performClick()
        type(a, "99")
        chip(a, "Bills").performClick()
        a.findViewById<View>(R.id.entry_date_yesterday).performClick()
        assertTrue(a.findViewById<View>(R.id.entry_date_yesterday).isSelected)
        a.findViewById<View>(R.id.entry_save).performClick()
        c.pause().stop().destroy()
        store().use { val e = it.all(owner).single(); assertEquals("Bills", e.category); assertEquals(today.minusDays(1).toString(), e.occurredOn); assertEquals(9900L, e.amountMinor) }
    }

    @Test fun totalsFollowEditAndDeleteAndMatchTheList() {
        store().use { s -> s.add(owner, 5000, "Food", todayText, "Today"); s.add(owner, 20000, "Bills", today.minusDays(1).toString(), "Yesterday bill") }
        val c = launch(); val a = c.get()
        assertEquals(Fmt.money(5000), text(a, R.id.hero_amount))

        children(a.findViewById(R.id.recent)).first { allText(it).contains("Yesterday bill") }.performClick()
        assertEquals("Edit expense", text(a, R.id.entry_title))
        assertEquals("₹200", text(a, R.id.entry_amount))
        repeat(3) { a.findViewById<View>(R.id.key_back).performClick() }
        type(a, "300")
        a.findViewById<View>(R.id.entry_save).performClick()
        store().use { assertEquals(30000L, it.all(owner).first { e -> e.note == "Yesterday bill" }.amountMinor) }

        children(a.findViewById(R.id.recent)).first { allText(it).contains("Yesterday bill") }.performClick()
        a.findViewById<View>(R.id.entry_delete).performClick()
        confirmDialog()
        assertFalse(allText(a.findViewById(R.id.recent)).contains("Yesterday bill"))
        store().use { assertEquals(listOf("Today"), it.all(owner).map { e -> e.note }) }
        c.pause().stop().destroy()
    }

    @Test fun emptyHomeShowsGuidanceAndABudgetNudge() {
        val c = launch(); val a = c.get()
        assertTrue(allText(a.findViewById(R.id.categories)).contains("Add your first expense"))
        assertTrue(allText(a.findViewById(R.id.recent)).contains("Nothing recorded"))
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.budget_nudge).visibility)
        assertEquals(View.GONE, a.findViewById<View>(R.id.today_row).visibility)
        assertEquals("Start your streak", text(a, R.id.streak_title))
        c.pause().stop().destroy()
    }

    @Test fun otherAccountsEntriesNeverAppear() {
        store().use { it.add("someone-else", 99900, "Food", todayText, "Secret") }
        val c = launch(); val a = c.get()
        assertFalse(allText(a.findViewById(R.id.screen)).contains("999"))
        c.pause().stop().destroy()
    }

    // ---------- streak ----------

    @Test fun streakCountsConsecutiveLoggedDays() {
        store().use { s -> for (back in 0..2) s.add(owner, 1000, "Food", today.minusDays(back.toLong()).toString(), "") ; s.add(owner, 1000, "Food", today.minusDays(4).toString(), "") }
        val c = launch(); val a = c.get()
        assertEquals("3 days in a row", text(a, R.id.streak_title))
        assertEquals(7, a.findViewById<LinearLayout>(R.id.streak_week).childCount)
        assertTrue(text(a, R.id.streak_sub).contains("Today is logged"))
        c.pause().stop().destroy()
    }

    // ---------- budget ----------

    private fun fillBudget(a: MainActivity, salary: String, payday: Int, fixed: List<Pair<String, String>>) {
        a.findViewById<View>(R.id.nav_settings).performClick()
        a.findViewById<View>(R.id.settings_budget_card).performClick()
        a.findViewById<EditText>(R.id.budget_income).setText(salary)
        repeat(Math.floorMod(payday - 1, 31)) { a.findViewById<View>(R.id.payday_plus).performClick() }  // default payday is 27
        for ((name, amount) in fixed) {
            a.findViewById<View>(R.id.fixed_add).performClick()
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            dialog.findViewById<EditText>(R.id.fixed_name_input).setText(name)
            dialog.findViewById<EditText>(R.id.fixed_amount_input).setText(amount)
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
        }
        a.findViewById<View>(R.id.budget_save).performClick()
    }

    @Test fun budgetSetupDrivesLeftToSpendOnHome() {
        store().use { it.add(owner, 25050, "Food", todayText, "Lunch") }
        val c = launch(); val a = c.get()
        a.findViewById<View>(R.id.nav_settings).performClick()
        a.findViewById<View>(R.id.settings_budget_card).performClick()
        assertEquals("27th", text(a, R.id.payday_value))                                   // starts at the 27th
        a.findViewById<EditText>(R.id.budget_income).setText("50000")
        a.findViewById<View>(R.id.fixed_add).performClick()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        dialog.findViewById<EditText>(R.id.fixed_name_input).setText("Rent")
        dialog.findViewById<EditText>(R.id.fixed_amount_input).setText("10000")
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
        assertEquals("Fixed ₹10,000 · Spendable ₹40,000", text(a, R.id.fixed_totals))
        a.findViewById<View>(R.id.budget_save).performClick()                               // returns to Settings
        assertTrue(text(a, R.id.settings_budget_title).contains("Salary ₹50,000 · paid on the 27th"))
        assertTrue(text(a, R.id.settings_budget_sub).contains("waiting to sync"))

        a.findViewById<View>(R.id.nav_home).performClick()
        val cycle = Cycles.containing(today, 27)
        val left = 4_000_000L - 25_050L
        assertEquals("LEFT TO SPEND · ${Fmt.range(cycle, 27)}".lowercase(), text(a, R.id.hero_label).lowercase())
        assertEquals(Fmt.money(left), text(a, R.id.hero_amount))
        val daysLeft = java.time.temporal.ChronoUnit.DAYS.between(today, cycle.end).toInt() + 1
        assertTrue(text(a, R.id.hero_chip1).contains(Fmt.money(left / daysLeft)))
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.today_row).visibility)
        assertEquals(View.GONE, a.findViewById<View>(R.id.budget_nudge).visibility)
        c.pause().stop().destroy()
        BudgetStore(app).let { b -> val p = b.get(owner)!!; assertEquals(5_000_000L, p.incomeMinor); assertEquals(27, p.payday); assertEquals(listOf("Rent"), p.fixed.map { it.name }); assertTrue(b.dirty(owner)) }
    }

    @Test fun overBudgetIsShownAsOverNotHidden() {
        BudgetStore(app).save(owner, 100_000, 1, listOf(FixedSpend("r", "Rent", 90_000, "Bills")))
        store().use { it.add(owner, 25_000, "Food", todayText, "") }
        val c = launch(); val a = c.get()
        assertEquals("Over by ${Fmt.money(15_000)}", text(a, R.id.hero_amount))
        assertEquals("Over budget", text(a, R.id.hero_chip1))
        c.pause().stop().destroy()
    }

    @Test fun budgetCannotBeSavedWithoutASalary() {
        val c = launch(); val a = c.get()
        a.findViewById<View>(R.id.nudge_button).performClick()
        a.findViewById<View>(R.id.budget_save).performClick()
        assertTrue(text(a, R.id.budget_error).contains("salary"))
        assertNull(BudgetStore(app).get(owner))
        c.pause().stop().destroy()
    }

    @Test fun fixedSpendNeedsANameAndAnAmountAndCanBeRemoved() {
        val c = launch(); val a = c.get()
        a.findViewById<View>(R.id.nudge_button).performClick()
        a.findViewById<View>(R.id.fixed_add).performClick()
        val d = ShadowAlertDialog.getLatestAlertDialog()
        d.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
        assertTrue(d.findViewById<TextView>(R.id.fixed_error).text.contains("name"))
        d.findViewById<EditText>(R.id.fixed_name_input).setText("Rent")
        d.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
        assertTrue(d.findViewById<TextView>(R.id.fixed_error).text.contains("amount"))
        d.findViewById<EditText>(R.id.fixed_amount_input).setText("12000.50")
        d.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
        assertEquals(1, a.findViewById<LinearLayout>(R.id.fixed_list).childCount)
        assertTrue(allText(a.findViewById(R.id.fixed_list)).contains("₹12,000.50"))
        a.findViewById<View>(R.id.fixed_list).let { (it as LinearLayout).getChildAt(0).findViewById<View>(R.id.fixed_delete).performClick() }
        assertEquals(0, a.findViewById<LinearLayout>(R.id.fixed_list).childCount)
        c.pause().stop().destroy()
    }

    // ---------- history and insights ----------

    @Test fun historyShowsTheCycleAndNavigatesBack() {
        BudgetStore(app).save(owner, 5_000_000, 27, emptyList())
        val cycle = Cycles.containing(today, 27)
        store().use { s -> s.add(owner, 12_000, "Food", todayText, "Now"); s.add(owner, 7_000, "Bills", cycle.start.minusDays(1).toString(), "Last cycle") }
        val c = launch(); val a = c.get()
        a.findViewById<View>(R.id.nav_history).performClick()
        assertEquals(Fmt.range(cycle, 27), text(a, R.id.history_cycle))
        assertTrue(allText(a.findViewById(R.id.history_list)).contains("Now"))
        assertTrue(allText(a.findViewById(R.id.history_list)).contains("Tap an expense to edit or delete it"))
        assertFalse(allText(a.findViewById(R.id.history_list)).contains("Last cycle"))
        assertFalse(a.findViewById<View>(R.id.next_cycle).isEnabled)
        a.findViewById<View>(R.id.prev_cycle).performClick()
        assertEquals(Fmt.range(Cycles.previous(cycle, 27), 27), text(a, R.id.history_cycle))
        assertTrue(allText(a.findViewById(R.id.history_list)).contains("Last cycle"))
        assertTrue(a.findViewById<View>(R.id.next_cycle).isEnabled)
        c.pause().stop().destroy()
    }

    @Test fun insightsShowSpentFixedLeftPaceAndCategories() {
        BudgetStore(app).save(owner, 5_000_000, 27, listOf(FixedSpend("r", "Rent", 1_000_000, "Bills")))
        store().use { s -> s.add(owner, 300_000, "Food", todayText, "Groceries"); s.add(owner, 100_000, "Transport", todayText, "") }
        val c = launch(); val a = c.get()
        a.findViewById<View>(R.id.nav_insights).performClick()
        val strip = allText(a.findViewById(R.id.ins_strip))
        assertTrue(strip, strip.contains("₹40,000") && strip.contains("₹10,000") && strip.contains("₹36,000"))   // spent 4,000; fixed 10,000; left 36,000 of 40,000
        assertTrue(strip.contains("Of ₹40,000"))
        assertTrue(text(a, R.id.ins_pace_text).startsWith("At this pace you'll finish"))
        val cats = allText(a.findViewById(R.id.ins_categories))
        assertTrue(cats, cats.indexOf("Food") < cats.indexOf("Transport") && cats.contains("75%"))
        val grid = allText(a.findViewById(R.id.ins_grid1)) + allText(a.findViewById(R.id.ins_grid2))
        assertTrue(grid, grid.contains("Groceries") && grid.contains("No-spend days"))
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.ins_pace_card).visibility)
        a.findViewById<View>(R.id.seg_last).performClick()
        assertEquals(Fmt.range(Cycles.previous(Cycles.containing(today, 27), 27), 27), text(a, R.id.ins_range))
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.ins_empty).visibility)
        c.pause().stop().destroy()
    }

    @Test fun insightsWithoutABudgetHideBudgetOnlyParts() {
        store().use { it.add(owner, 50_000, "Food", todayText, "") }
        val c = launch(); val a = c.get()
        a.findViewById<View>(R.id.nav_insights).performClick()
        assertEquals(View.GONE, a.findViewById<View>(R.id.ins_pace_card).visibility)
        assertEquals(1, a.findViewById<LinearLayout>(R.id.ins_strip).childCount)            // only "Spent"
        assertEquals("This month", text(a, R.id.seg_current))
        c.pause().stop().destroy()
    }

    // ---------- reminder ----------

    private fun alarmManager() = shadowOf(app.getSystemService(AlarmManager::class.java))
    private fun nextAlarm(): ShadowAlarmManager.ScheduledAlarm? = alarmManager().peekNextScheduledAlarm()

    @Test fun reminderSchedulesAtChosenTimeReschedulesAndCancels() {
        assertNull(nextAlarm())
        ReminderScheduler.set(app, true, 21, 30)
        val fires = java.time.Instant.ofEpochMilli(nextAlarm()!!.triggerAtMs).atZone(ZoneId.systemDefault()).toLocalDateTime()
        assertEquals(21, fires.hour); assertEquals(30, fires.minute)
        assertTrue(fires.isAfter(LocalDateTime.now().minusSeconds(1)))
        assertTrue(fires.isBefore(LocalDateTime.now().plusDays(1).plusMinutes(1)))
        ReminderScheduler.set(app, true, 7, 5)
        val moved = java.time.Instant.ofEpochMilli(nextAlarm()!!.triggerAtMs).atZone(ZoneId.systemDefault()).toLocalDateTime()
        assertEquals(7, moved.hour); assertEquals(5, moved.minute)
        assertEquals(1, alarmManager().scheduledAlarms.size)
        alarmManager().scheduledAlarms.clear()
        app.sendBroadcast(Intent(Intent.ACTION_BOOT_COMPLETED)); idle()
        assertNotNull(nextAlarm())
        ReminderScheduler.set(app, false)
        assertNull(nextAlarm())
    }

    @Test fun notificationHasNoAmountAndTapOpensEntry() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        store().use { it.add(owner, 123456, "Food", todayText, "Dinner") }
        ReminderScheduler.set(app, true, 22, 0)
        ReminderScheduler.show(app)
        val manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val posted = shadowOf(manager).allNotifications.single()
        val body = "${posted.extras.getCharSequence(Notification.EXTRA_TITLE)} ${posted.extras.getCharSequence(Notification.EXTRA_TEXT)}"
        assertFalse(body, body.contains(Regex("[0-9₹]")))
        val launched = shadowOf(posted.contentIntent).savedIntent
        assertTrue(launched.getBooleanExtra("open_add", false))
        val c = Robolectric.buildActivity(MainActivity::class.java, launched).setup()
        assertEquals("Add expense", text(c.get(), R.id.entry_title))
        c.pause().stop().destroy()
    }

    @Test fun reminderIsSilentWithoutNotificationPermission() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        ReminderScheduler.set(app, true, 22, 0)
        ReminderScheduler.show(app)
        assertTrue(shadowOf(app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).allNotifications.isEmpty())
    }

    @Test fun enablingReminderWithoutPermissionKeepsItOffAndExplains() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val c = launch(); val a = c.get()
        a.findViewById<View>(R.id.nav_settings).performClick()
        val toggle = a.findViewById<Switch>(R.id.reminder_switch)
        toggle.isChecked = true
        assertFalse(toggle.isChecked); assertFalse(ReminderScheduler.enabled(app)); assertNull(nextAlarm())
        assertTrue(text(a, R.id.reminder_help).contains("Notifications are off"))
        c.pause().stop().destroy()
    }

    @Test fun enablingReminderWithPermissionSchedulesIt() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val c = launch(); val a = c.get()
        a.findViewById<View>(R.id.nav_settings).performClick()
        a.findViewById<Switch>(R.id.reminder_switch).isChecked = true
        assertTrue(ReminderScheduler.enabled(app)); assertNotNull(nextAlarm())
        c.pause().stop().destroy()
    }
}
