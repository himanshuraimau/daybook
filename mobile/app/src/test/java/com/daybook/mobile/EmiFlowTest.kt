package com.daybook.mobile

import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.daybook.mobile.data.AccountSession
import com.daybook.mobile.data.BudgetStore
import com.daybook.mobile.data.ExpenseStore
import com.daybook.mobile.data.SessionVault
import com.daybook.mobile.domain.Cycles
import com.daybook.mobile.domain.FixedSpend
import com.daybook.mobile.ui.Fmt
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import java.time.LocalDate
import java.time.YearMonth

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class EmiFlowTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val owner = "owner-1"
    private val today = LocalDate.now()
    private val thisKey = YearMonth.from(Cycles.containing(today, 27).start)

    @Before fun setUp() {
        com.daybook.mobile.data.ApiClient.baseUrl = ""
        FakeAndroidKeyStore.install()
        app.deleteDatabase("daybook.db"); app.getSharedPreferences("budget", 0).edit().clear().commit()
        SessionVault(app).save(AccountSession(owner, "me@example.com", "token"))
    }
    @After fun tearDown() { app.deleteDatabase("daybook.db"); app.getSharedPreferences("budget", 0).edit().clear().commit(); SessionVault(app).clear() }

    private fun launch() = Robolectric.buildActivity(MainActivity::class.java).setup()
    private fun text(a: MainActivity, id: Int) = a.findViewById<TextView>(id).text.toString()
    private fun allText(v: View): String = if (v is android.view.ViewGroup) (0 until v.childCount).joinToString("\\n") { allText(v.getChildAt(it)) } else (v as? TextView)?.text?.toString().orEmpty()
    private fun type(d: android.app.Dialog, id: Int, value: String) { d.findViewById<EditText>(id).apply { requestFocus(); setText(value) } }
    private fun dialog() = ShadowAlertDialog.getLatestAlertDialog()
    private fun openBudgetEmi(a: MainActivity, salary: String = "50000") {
        a.findViewById<View>(R.id.nav_settings).performClick()
        a.findViewById<View>(R.id.settings_budget_card).performClick()
        a.findViewById<EditText>(R.id.budget_income).setText(salary)
        a.findViewById<View>(R.id.emi_add).performClick()
    }

    @Test fun emiDialogKeepsInstalmentMonthsAndTotalConsistent() {
        val c = launch(); val a = c.get()
        openBudgetEmi(a)
        val d = dialog()
        assertEquals("Add EMI", d.findViewById<TextView>(R.id.fixed_title).text.toString())
        assertEquals(View.VISIBLE, d.findViewById<View>(R.id.emi_group).visibility)
        type(d, R.id.fixed_months_input, "12")
        type(d, R.id.fixed_total_input, "60000")                                   // total -> instalment
        assertEquals("5000", d.findViewById<EditText>(R.id.fixed_amount_input).text.toString())
        assertEquals("₹5,000 × 12 months = ₹60,000", d.findViewById<TextView>(R.id.fixed_summary).text.toString())
        type(d, R.id.fixed_amount_input, "4000")                                   // instalment -> total
        assertEquals("48000", d.findViewById<EditText>(R.id.fixed_total_input).text.toString())
        type(d, R.id.fixed_months_input, "6")                                      // months -> total
        assertEquals("24000", d.findViewById<EditText>(R.id.fixed_total_input).text.toString())
        c.pause().stop().destroy()
    }

    @Test fun unevenTotalIsRoundedToTheNearestPaise() {
        val c = launch(); val a = c.get()
        openBudgetEmi(a)
        val d = dialog()
        type(d, R.id.fixed_months_input, "3")
        type(d, R.id.fixed_total_input, "10000")
        assertEquals("3333.33", d.findViewById<EditText>(R.id.fixed_amount_input).text.toString())
        c.pause().stop().destroy()
    }

    @Test fun emiStartingThisCycleIsListedCountedAndSaved() {
        val c = launch(); val a = c.get()
        openBudgetEmi(a)
        val d = dialog()
        type(d, R.id.fixed_name_input, "Phone EMI"); type(d, R.id.fixed_amount_input, "5000"); type(d, R.id.fixed_months_input, "12")
        d.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
        assertFalse(d.isShowing)
        val list = a.findViewById<LinearLayout>(R.id.fixed_list)
        assertEquals(1, list.childCount)
        assertTrue(allText(list), allText(list).contains("EMI · 1 of 12 · total ₹60,000"))
        assertEquals("Fixed ₹5,000 · Spendable ₹45,000", text(a, R.id.fixed_totals))
        a.findViewById<View>(R.id.budget_save).performClick()
        val p = BudgetStore(app).get(owner)!!.fixed.single()
        assertEquals(12, p.months); assertEquals(thisKey.toString(), p.startKey); assertEquals(500_000L, p.amountMinor)
        a.findViewById<View>(R.id.nav_home).performClick()
        assertEquals(Fmt.money(4_500_000L), text(a, R.id.hero_amount))
        c.pause().stop().destroy()
    }

    @Test fun emiThatIsPartlyPaidStartsInThePast() {
        val c = launch(); val a = c.get()
        openBudgetEmi(a)
        val d = dialog()
        type(d, R.id.fixed_name_input, "Laptop"); type(d, R.id.fixed_amount_input, "2000"); type(d, R.id.fixed_months_input, "10")
        type(d, R.id.fixed_paid_input, "4")
        d.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
        assertTrue(allText(a.findViewById(R.id.fixed_list)).contains("EMI · 5 of 10 · total ₹20,000"))
        a.findViewById<View>(R.id.budget_save).performClick()
        assertEquals(thisKey.minusMonths(4).toString(), BudgetStore(app).get(owner)!!.fixed.single().startKey)
        c.pause().stop().destroy()
    }

    @Test fun emiStartingNextCycleIsNotCountedYet() {
        val c = launch(); val a = c.get()
        openBudgetEmi(a)
        val d = dialog()
        type(d, R.id.fixed_name_input, "Fridge"); type(d, R.id.fixed_amount_input, "3000"); type(d, R.id.fixed_months_input, "6")
        d.findViewById<View>(R.id.fixed_start_next).performClick()
        assertEquals(View.GONE, d.findViewById<View>(R.id.fixed_paid_input).visibility)
        d.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
        assertTrue(allText(a.findViewById(R.id.fixed_list)).contains("EMI · starts next cycle"))
        assertEquals("Fixed ₹0 · Spendable ₹50,000", text(a, R.id.fixed_totals))
        a.findViewById<View>(R.id.budget_save).performClick()
        assertEquals(thisKey.plusMonths(1).toString(), BudgetStore(app).get(owner)!!.fixed.single().startKey)
        c.pause().stop().destroy()
    }

    @Test fun emiDialogRejectsMissingOrImpossibleValues() {
        val c = launch(); val a = c.get()
        openBudgetEmi(a)
        val d = dialog()
        val err = { d.findViewById<TextView>(R.id.fixed_error).text.toString() }
        d.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick(); assertTrue(err().contains("name"))
        type(d, R.id.fixed_name_input, "Phone EMI"); d.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick(); assertTrue(err().contains("instalment"))
        type(d, R.id.fixed_amount_input, "5000"); d.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick(); assertTrue(err().contains("months"))
        type(d, R.id.fixed_months_input, "121"); d.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick(); assertTrue(err().contains("1 to 120"))
        type(d, R.id.fixed_months_input, "12"); type(d, R.id.fixed_paid_input, "12"); d.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
        assertTrue(err(), err().contains("already paid"))
        assertTrue(d.isShowing)
        assertEquals(0, a.findViewById<LinearLayout>(R.id.fixed_list).childCount)
        c.pause().stop().destroy()
    }

    @Test fun plainFixedSpendDialogHasNoEmiFields() {
        val c = launch(); val a = c.get()
        a.findViewById<View>(R.id.nav_settings).performClick()
        a.findViewById<View>(R.id.settings_budget_card).performClick()
        a.findViewById<View>(R.id.fixed_add).performClick()
        val d = dialog()
        assertEquals("Add fixed spend", d.findViewById<TextView>(R.id.fixed_title).text.toString())
        assertEquals(View.GONE, d.findViewById<View>(R.id.emi_group).visibility)
        c.pause().stop().destroy()
    }

    @Test fun finishedEmiStopsReducingWhatIsLeft() {
        val old = FixedSpend("e", "Old EMI", 500_000, "Shopping", 3, thisKey.minusMonths(5).toString())   // ended two cycles ago
        BudgetStore(app).save(owner, 5_000_000, 27, listOf(FixedSpend("r", "Rent", 1_000_000, "Bills"), old))
        val c = launch(); val a = c.get()
        assertEquals(Fmt.money(4_000_000L), text(a, R.id.hero_amount))
        a.findViewById<View>(R.id.nav_settings).performClick()
        assertEquals("Fixed ₹10,000 · Spendable ₹40,000", text(a, R.id.settings_budget_sub).lines().first())
        a.findViewById<View>(R.id.settings_budget_card).performClick()
        assertTrue(allText(a.findViewById(R.id.fixed_list)).contains("EMI · finished"))
        c.pause().stop().destroy()
    }

    @Test fun addExpenseWithoutABudgetSendsYouToSetOneUpFirst() {
        val c = launch(); val a = c.get()
        a.findViewById<View>(R.id.prompt_add).performClick()
        a.findViewById<View>(R.id.entry_emi_link).performClick()
        assertNotNull(a.findViewById<View>(R.id.budget_income))                    // landed on the Budget screen
        c.pause().stop().destroy()
    }

    @Test fun addExpenseCanBecomeAnEmiInsteadOfAOneOffExpense() {
        BudgetStore(app).save(owner, 5_000_000, 27, listOf(FixedSpend("r", "Rent", 1_000_000, "Bills")))
        val c = launch(); val a = c.get()
        a.findViewById<View>(R.id.prompt_add).performClick()
        for (k in "2500") a.findViewById<View>(a.resources.getIdentifier("key_$k", "id", a.packageName)).performClick()
        a.findViewById<EditText>(R.id.entry_note).setText("Washing machine")
        a.findViewById<View>(R.id.entry_emi_link).performClick()
        val d = dialog()
        assertEquals("Washing machine", d.findViewById<EditText>(R.id.fixed_name_input).text.toString())
        assertEquals("2500", d.findViewById<EditText>(R.id.fixed_amount_input).text.toString())   // typed amount is the instalment
        type(d, R.id.fixed_months_input, "9")
        assertEquals("22500", d.findViewById<EditText>(R.id.fixed_total_input).text.toString())
        d.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
        // back on Home with the EMI reserved and no one-off expense saved
        assertEquals(Fmt.money(5_000_000L - 1_000_000L - 250_000L), text(a, R.id.hero_amount))
        val p = BudgetStore(app).get(owner)!!
        assertEquals(listOf("Rent", "Washing machine"), p.fixed.map { it.name })
        assertEquals(9, p.fixed[1].months)
        c.pause().stop().destroy()
        ExpenseStore(app).use { assertEquals(0, it.all(owner).size) }
    }

    @Test fun editingAnExistingExpenseDoesNotOfferAnEmi() {
        ExpenseStore(app).use { it.add(owner, 5000, "Food", today.toString(), "Lunch") }
        val c = launch(); val a = c.get()
        val recent = a.findViewById<LinearLayout>(R.id.recent)
        recent.getChildAt(0).performClick()
        assertEquals(View.GONE, a.findViewById<View>(R.id.entry_emi_link).visibility)
        c.pause().stop().destroy()
    }
}
