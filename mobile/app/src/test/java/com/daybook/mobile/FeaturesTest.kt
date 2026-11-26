package com.daybook.mobile

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import com.daybook.mobile.data.AccountSession
import com.daybook.mobile.data.BudgetStore
import com.daybook.mobile.data.ExpenseStore
import com.daybook.mobile.data.SessionVault
import com.daybook.mobile.domain.Cycles
import com.daybook.mobile.domain.FixedSpend
import com.daybook.mobile.domain.OneOffIncome
import com.daybook.mobile.reminder.BudgetAlertNotifier
import com.daybook.mobile.ui.Fmt
import com.daybook.mobile.widget.DaybookWidget
import com.daybook.mobile.widget.WidgetContent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FeaturesTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val owner = "owner-1"
    private val today = LocalDate.now()

    @Before fun setUp() {
        com.daybook.mobile.data.ApiClient.baseUrl = ""
        FakeAndroidKeyStore.install()
        app.deleteDatabase("daybook.db")
        for (n in listOf("budget", "budget_alerts", "appearance")) app.getSharedPreferences(n, 0).edit().clear().commit()
        SessionVault(app).save(AccountSession(owner, "me@example.com", "token"))
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }
    @After fun tearDown() { app.deleteDatabase("daybook.db"); for (n in listOf("budget", "budget_alerts", "appearance")) app.getSharedPreferences(n, 0).edit().clear().commit(); SessionVault(app).clear() }

    private fun launch(intent: Intent? = null) = (if (intent == null) Robolectric.buildActivity(MainActivity::class.java) else Robolectric.buildActivity(MainActivity::class.java, intent)).setup()
    private fun text(a: MainActivity, id: Int) = a.findViewById<TextView>(id).text.toString()
    private fun allText(v: View): String = if (v is android.view.ViewGroup) (0 until v.childCount).joinToString("\\n") { allText(v.getChildAt(it)) } else (v as? TextView)?.text?.toString().orEmpty()
    private fun spend(vararg amounts: Pair<Long, String>) = ExpenseStore(app).use { s -> amounts.forEach { (paise, note) -> s.add(owner, paise, "Food", today.toString(), note) } }
    private val manager get() = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private fun posted() = shadowOf(manager).allNotifications
    private fun bodyOf(n: Notification) = "${n.extras.getCharSequence(Notification.EXTRA_TITLE)} ${n.extras.getCharSequence(Notification.EXTRA_TEXT)}"
    private fun budget(income: Long = 5_000_000, payday: Int = 27, savings: Long = 0, incomes: List<OneOffIncome> = emptyList(), fixed: List<FixedSpend> = emptyList()) =
        BudgetStore(app).save(owner, income, payday, fixed, savings, incomes)

    // ---------- quick picks ----------
    @Test fun quickPicksFillAmountCategoryAndNote() {
        ExpenseStore(app).use { s ->
            repeat(3) { s.add(owner, 2_000, "Food", today.minusDays(it + 2L).toString(), "Chai") }
            s.add(owner, 15_000, "Transport", today.minusDays(1).toString(), "Auto")
        }
        val c = launch(); val a = c.get()
        a.findViewById<View>(R.id.prompt_add).performClick()
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.entry_picks_scroll).visibility)
        val chips = a.findViewById<LinearLayout>(R.id.entry_picks)
        assertEquals(2, chips.childCount)
        assertEquals("Repeat · Auto ₹150", (chips.getChildAt(0) as TextView).text.toString())
        assertEquals("Chai ₹20", (chips.getChildAt(1) as TextView).text.toString())
        chips.getChildAt(1).performClick()
        assertEquals("₹20", text(a, R.id.entry_amount))
        assertEquals("Chai", text(a, R.id.entry_note))
        assertTrue(a.findViewById<android.view.ViewGroup>(R.id.entry_categories).let { g -> (0 until g.childCount).map { g.getChildAt(it) as TextView }.first { it.text == "Food" }.isSelected })
        chips.getChildAt(0).performClick()                              // repeat last switches everything over
        assertEquals("₹150", text(a, R.id.entry_amount)); assertEquals("Auto", text(a, R.id.entry_note))
        a.findViewById<View>(R.id.entry_save).performClick()
        c.pause().stop().destroy()
        ExpenseStore(app).use { val e = it.all(owner).first { x -> x.amountMinor == 15_000L && x.occurredOn == today.toString() }; assertEquals("Transport", e.category); assertEquals("Auto", e.note) }
    }

    @Test fun noQuickPicksWithoutHistoryOrWhenEditing() {
        val c = launch(); val a = c.get()
        a.findViewById<View>(R.id.prompt_add).performClick()
        assertEquals(View.GONE, a.findViewById<View>(R.id.entry_picks_scroll).visibility)
        c.pause().stop().destroy()
        spend(5_000L to "Lunch")
        val c2 = launch(); val a2 = c2.get()
        a2.findViewById<LinearLayout>(R.id.recent).getChildAt(0).performClick()           // editing an expense
        assertEquals(View.GONE, a2.findViewById<View>(R.id.entry_picks_scroll).visibility)
        c2.pause().stop().destroy()
    }

    // ---------- savings and one-off income ----------
    @Test fun savingsAndOneOffIncomeChangeWhatIsLeft() {
        val c = launch(); val a = c.get()
        a.findViewById<View>(R.id.nav_settings).performClick()
        a.findViewById<View>(R.id.settings_budget_card).performClick()
        a.findViewById<EditText>(R.id.budget_income).setText("50000")
        a.findViewById<EditText>(R.id.budget_savings).setText("5000")
        assertEquals("Fixed ₹0 · Saving ₹5,000 · Spendable ₹45,000", text(a, R.id.fixed_totals))
        a.findViewById<View>(R.id.income_add).performClick()
        val d = ShadowAlertDialog.getLatestAlertDialog()
        d.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
        assertTrue(d.findViewById<TextView>(R.id.income_error).text.contains("name"))
        d.findViewById<EditText>(R.id.income_name_input).setText("Diwali bonus")
        d.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
        assertTrue(d.findViewById<TextView>(R.id.income_error).text.contains("amount"))
        d.findViewById<EditText>(R.id.income_amount_input).setText("10000")
        d.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()                // dated today, so it counts in this cycle
        assertEquals("Fixed ₹0 · Saving ₹5,000 · Extra income ₹10,000 · Spendable ₹55,000", text(a, R.id.fixed_totals))
        assertTrue(allText(a.findViewById(R.id.income_list)).contains("+₹10,000"))
        a.findViewById<View>(R.id.budget_save).performClick()
        val p = BudgetStore(app).get(owner)!!
        assertEquals(500_000L, p.savingsMinor); assertEquals(listOf("Diwali bonus"), p.incomes.map { it.name }); assertEquals(today.toString(), p.incomes[0].date)
        a.findViewById<View>(R.id.nav_home).performClick()
        assertEquals(Fmt.money(5_500_000L), text(a, R.id.hero_amount))
        c.pause().stop().destroy()
    }

    @Test fun incomeFromAnEarlierCycleDoesNotRaiseThisCyclesBudget() {
        val old = OneOffIncome("o", "Old bonus", 9_000_000, Cycles.containing(today, 27).start.minusDays(3).toString())
        budget(incomes = listOf(old))
        val c = launch(); val a = c.get()
        assertEquals(Fmt.money(5_000_000L), text(a, R.id.hero_amount))
        c.pause().stop().destroy()
    }

    @Test fun invalidSavingsTargetIsRejectedAndRemovingIncomeWorks() {
        val bonus = OneOffIncome("b", "Bonus", 100_000, today.toString())
        budget(incomes = listOf(bonus))
        val c = launch(); val a = c.get()
        a.findViewById<View>(R.id.nav_settings).performClick(); a.findViewById<View>(R.id.settings_budget_card).performClick()
        a.findViewById<EditText>(R.id.budget_savings).setText("abc")
        a.findViewById<View>(R.id.budget_save).performClick()
        assertTrue(text(a, R.id.budget_error).contains("savings target"))
        a.findViewById<EditText>(R.id.budget_savings).setText("0")                         // zero is fine: no target
        (a.findViewById<LinearLayout>(R.id.income_list).getChildAt(0)).findViewById<View>(R.id.fixed_delete).performClick()
        a.findViewById<View>(R.id.budget_save).performClick()
        val p = BudgetStore(app).get(owner)!!
        assertEquals(0L, p.savingsMinor); assertTrue(p.incomes.isEmpty())
        c.pause().stop().destroy()
    }

    // ---------- budget alerts ----------
    @Test fun alertsStaySilentUntilEnabledThenAnnounceEachLevelOncePerCycle() {
        budget(income = 100_000)                                          // ₹1,000 to spend
        spend(85_000L to "")                                              // 85%
        BudgetAlertNotifier.check(app); assertTrue(posted().isEmpty())     // off by default
        BudgetAlertNotifier.set(app, true)
        BudgetAlertNotifier.check(app)
        assertEquals(1, posted().size)
        val first = bodyOf(posted()[0])
        assertFalse(first, first.contains(Regex("[0-9₹]")))               // never an amount
        assertTrue(first.contains("used most"))
        BudgetAlertNotifier.check(app); assertEquals(1, shadowOf(manager).size())       // same level: not repeated
        spend(20_000L to "")                                              // now over
        BudgetAlertNotifier.check(app)
        assertTrue(bodyOf(shadowOf(manager).getNotification(73)), bodyOf(shadowOf(manager).getNotification(73)).contains("over"))
        val before = shadowOf(manager).allNotifications.size
        BudgetAlertNotifier.check(app); assertEquals(before, shadowOf(manager).allNotifications.size)
    }

    @Test fun alertsDoNothingWithoutPermissionOrBudget() {
        BudgetAlertNotifier.set(app, true)
        spend(85_000L to "")
        BudgetAlertNotifier.check(app); assertTrue(posted().isEmpty())     // no budget yet
        budget(income = 100_000)
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        BudgetAlertNotifier.check(app); assertTrue(posted().isEmpty())
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        BudgetAlertNotifier.check(app); assertEquals(1, posted().size)     // permission came later: still announced, it was not recorded as sent
    }

    @Test fun anAlertForANewCycleIsAnnouncedAgain() {
        budget(income = 100_000)
        BudgetAlertNotifier.set(app, true)
        spend(85_000L to "")
        BudgetAlertNotifier.check(app, today)
        val nextCycleDay = Cycles.next(Cycles.containing(today, 27), 27).start.plusDays(1)
        ExpenseStore(app).use { it.add(owner, 85_000, "Food", nextCycleDay.toString(), "") }
        manager.cancelAll()                                               // the alert reuses one id, so clear it to see a new post
        BudgetAlertNotifier.check(app, nextCycleDay)
        assertEquals(1, posted().size)
    }

    @Test fun settingsSwitchTurnsAlertsOnWithPermissionAndExplainsOtherwise() {
        val c = launch(); val a = c.get()
        a.findViewById<View>(R.id.nav_settings).performClick()
        val sw = a.findViewById<Switch>(R.id.alerts_switch)
        assertFalse(sw.isChecked)
        sw.isChecked = true
        assertTrue(BudgetAlertNotifier.enabled(app))
        sw.isChecked = false
        assertFalse(BudgetAlertNotifier.enabled(app))
        c.pause().stop().destroy()
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val c2 = launch(); val a2 = c2.get()
        a2.findViewById<View>(R.id.nav_settings).performClick()
        a2.findViewById<Switch>(R.id.alerts_switch).isChecked = true
        assertFalse(a2.findViewById<Switch>(R.id.alerts_switch).isChecked); assertFalse(BudgetAlertNotifier.enabled(app))
        c2.pause().stop().destroy()
    }

    @Test fun tappingAnAlertOpensInsights() {
        budget(income = 100_000); BudgetAlertNotifier.set(app, true); spend(85_000L to "")
        BudgetAlertNotifier.check(app)
        val launched = shadowOf(posted()[0].contentIntent).savedIntent
        assertEquals("insights", launched.getStringExtra("open_screen"))
        val c = launch(launched)
        assertNotEquals(null, c.get().findViewById<View>(R.id.ins_strip))
        c.pause().stop().destroy()
    }

    // ---------- widget and shortcut ----------
    @Test fun widgetContentFollowsTheBudgetState() {
        SessionVault(app).clear()
        assertEquals(WidgetContent.Content("DAYBOOK", "Sign in", "Open the app to start"), WidgetContent.build(app))
        SessionVault(app).save(AccountSession(owner, "me@example.com", "token"))
        spend(25_050L to "Lunch")
        val plain = WidgetContent.build(app)
        assertEquals("SPENT TODAY", plain.label); assertEquals("₹250.50", plain.amount)
        budget(income = 5_000_000, fixed = listOf(FixedSpend("r", "Rent", 1_000_000, "Bills")))
        val b = WidgetContent.build(app)
        assertEquals("LEFT TO SPEND", b.label); assertEquals(Fmt.money(4_000_000L - 25_050L), b.amount); assertTrue(b.sub, b.sub.contains("a day"))
        budget(income = 20_000)                                           // ₹200 to spend, ₹250.50 spent
        val over = WidgetContent.build(app)
        assertEquals("OVER BUDGET", over.label); assertTrue(over.amount.startsWith("Over by"))
    }

    @Test fun widgetProviderDrawsTheContentAndWiresTheAddButton() {
        budget(income = 5_000_000); spend(1_000L to "")
        val mgr = AppWidgetManager.getInstance(app)
        val shadow = shadowOf(mgr)
        val id = shadow.createWidget(DaybookWidget::class.java, R.layout.widget_daybook)
        DaybookWidget().onUpdate(app, mgr, intArrayOf(id))
        val view = shadow.getViewFor(id)
        assertEquals("LEFT TO SPEND", view.findViewById<TextView>(R.id.widget_label).text.toString())
        assertEquals(Fmt.money(5_000_000L - 1_000L), view.findViewById<TextView>(R.id.widget_amount).text.toString())
        view.findViewById<View>(R.id.widget_add).performClick()
        val started = shadowOf(app).nextStartedActivity
        assertTrue(started.getBooleanExtra("open_add", false))
        DaybookWidget.refresh(app)                                         // no crash, and a refresh with the widget placed works
    }

    @Test fun launcherShortcutAndWidgetAreRegistered() {
        val info = app.packageManager.getActivityInfo(ComponentName(app, MainActivity::class.java), PackageManager.GET_META_DATA)
        assertNotEquals(0, info.metaData.getInt("android.app.shortcuts"))
        val widgets = app.packageManager.queryBroadcastReceivers(Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE).setPackage(app.packageName), 0)
        assertTrue(widgets.any { it.activityInfo.name == DaybookWidget::class.java.name })
    }

    @Test fun addExpenseShortcutOpensTheEntryScreen() {
        val c = launch(Intent(Intent.ACTION_VIEW).setClassName(app.packageName, MainActivity::class.java.name).putExtra("open_add", true))
        assertEquals("Add expense", text(c.get(), R.id.entry_title))
        c.pause().stop().destroy()
    }
}
