package com.daybook.mobile.ui

import android.app.AlertDialog
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.daybook.mobile.R
import com.daybook.mobile.data.AccountSession
import com.daybook.mobile.data.ApiClient
import com.daybook.mobile.data.BudgetStore
import com.daybook.mobile.data.ExpenseStore
import com.daybook.mobile.data.ExpenseSync
import com.daybook.mobile.data.LocalExpense
import java.time.LocalDate
import java.time.YearMonth

/** What the screens need from the activity: shared state, navigation and background work. */
interface Host {
    val context: Context
    val account: AccountSession
    val store: ExpenseStore
    val budgets: BudgetStore
    val api: ApiClient
    var promptVisible: Boolean
    var flamePop: Boolean
    /** 0 is the current cycle, -1 the one before, and so on. */
    var historyOffset: Int
    var insightsLast: Boolean
    fun inflate(layout: Int, name: String): View
    fun showAuth()
    fun showHome()
    fun showHistory(fetch: Boolean = true)
    fun showInsights()
    fun showSettings()
    fun showEntry(clientId: String? = null)
    fun showBudget(onboarding: Boolean = false)
    fun returnFromEntry()
    fun refreshFromServer(months: List<YearMonth> = listOf(YearMonth.now()))
    fun perform(work: () -> Unit, failure: (String) -> Unit)
    fun runOnUi(block: () -> Unit)
    fun message(text: String)
    fun today(): LocalDate = LocalDate.now()
    fun requestNotificationPermission()
    fun signedIn(session: AccountSession, isNew: Boolean)
    fun signOut()
    /** Rebuilds the screen so a changed light/dark choice takes effect. */
    fun recreateForAppearance()
}

object RankRow {
    /** Adds a labelled bar to [parent]; [fraction] is 0..1 of the track. */
    fun add(context: Context, parent: ViewGroup, label: String, amount: String, fraction: Float, colorRes: Int) {
        val v = LayoutInflater.from(context).inflate(R.layout.item_rank, parent, false)
        v.findViewById<TextView>(R.id.rank_label).text = label
        v.findViewById<TextView>(R.id.rank_amount).text = amount
        val bar = v.findViewById<View>(R.id.rank_bar)
        bar.background = android.graphics.drawable.GradientDrawable().apply { setColor(context.getColor(colorRes)); cornerRadius = Draw.dp(context, 6).toFloat() }
        (bar.layoutParams as LinearLayout.LayoutParams).weight = (fraction.coerceIn(0f, 1f) * 100f).coerceAtLeast(if (fraction > 0f) 3f else 0f)
        parent.addView(v)
    }
}

object ExpenseActions {
    /** Tapping a row edits it; a failed row first offers a retry. */
    fun open(h: Host, e: LocalExpense) {
        if (e.status == "failed") AlertDialog.Builder(h.context, R.style.DaybookDialog).setTitle("Sync failed")
            .setMessage("Your expense is saved on this phone.")
            .setPositiveButton("Retry") { _, _ ->
                h.store.retryFailed(h.account.ownerId); ExpenseSync.schedule(h.context)
                h.refreshFromServer(listOf(YearMonth.parse(e.occurredOn.substring(0, 7))))
            }
            .setNeutralButton("Review") { _, _ -> h.showEntry(e.clientId) }.show()
        else h.showEntry(e.clientId)
    }
}
