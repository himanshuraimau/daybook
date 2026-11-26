package com.daybook.mobile.ui

import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.TextView
import com.daybook.mobile.R
import com.daybook.mobile.data.ExpenseSync
import com.daybook.mobile.domain.Cycles
import com.daybook.mobile.domain.FixedSpend
import com.daybook.mobile.domain.OneOffIncome
import java.math.BigDecimal

class BudgetScreen(private val h: Host) {
    fun show(onboarding: Boolean, onDone: () -> Unit) {
        val v = h.inflate(R.layout.screen_budget, "budget")
        val ctx = h.context
        val owner = h.account.ownerId
        val existing = h.budgets.get(owner)
        val income = v.findViewById<EditText>(R.id.budget_income)
        val error = v.findViewById<TextView>(R.id.budget_error)
        var payday = existing?.payday ?: 27
        val fixed = ArrayList<FixedSpend>(existing?.fixed ?: emptyList())
        val incomes = ArrayList<OneOffIncome>(existing?.incomes ?: emptyList())
        val savings = v.findViewById<EditText>(R.id.budget_savings)
        if (existing != null && existing.savingsMinor > 0) savings.setText(BigDecimal.valueOf(existing.savingsMinor, 2).stripTrailingZeros().toPlainString())
        /** Blank or 0 means no savings target; null means the text is not a valid amount. */
        fun savingsPaise(): Long? = savings.text.toString().trim().let { if (it.isEmpty() || it.toBigDecimalOrNull()?.signum() == 0) 0L else Fmt.parsePaise(it) }
        if (existing != null) income.setText(BigDecimal.valueOf(existing.incomeMinor, 2).stripTrailingZeros().toPlainString())
        val skip = v.findViewById<TextView>(R.id.budget_skip)
        skip.visibility = if (onboarding) View.VISIBLE else View.GONE
        skip.setOnClickListener { onDone() }
        v.findViewById<View>(R.id.budget_back).setOnClickListener { onDone() }

        val totals = v.findViewById<TextView>(R.id.fixed_totals)
        val list = v.findViewById<LinearLayout>(R.id.fixed_list)
        fun refreshTotals() {
            val salary = Fmt.parsePaise(income.text.toString()) ?: 0L
            val cycleNow = Cycles.containing(h.today(), payday)
            val sum = fixed.filter { it.chargedIn(cycleNow) }.sumOf { it.amountMinor }
            val save = savingsPaise() ?: 0L
            val extra = incomes.filter { it.inCycle(cycleNow) }.sumOf { it.amountMinor }
            val spendable = salary + extra - sum - save
            if (salary > 0 && spendable < 0) { totals.text = "Fixed spends and savings are ${Fmt.money(-spendable)} more than your income"; totals.setTextColor(ctx.getColor(R.color.bad)) }
            else {
                totals.text = "Fixed ${Fmt.money(sum)}" + (if (save > 0) " · Saving ${Fmt.money(save)}" else "") + (if (extra > 0) " · Extra income ${Fmt.money(extra)}" else "") + (if (salary > 0) " · Spendable ${Fmt.money(spendable)}" else "")
                totals.setTextColor(ctx.getColor(R.color.ink))
            }
        }
        fun refreshList() {
            list.removeAllViews()
            for (f in fixed) {
                val row = LayoutInflater.from(ctx).inflate(R.layout.item_fixed, list, false)
                val meta = Categories.of(f.category); val color = ctx.getColor(meta.colorRes)
                row.findViewById<View>(R.id.fixed_icon_bg).background.mutate().setTint(Draw.blend(color, ctx.getColor(R.color.surface), 0.2f))
                row.findViewById<ImageView>(R.id.fixed_icon).apply { setImageResource(meta.icon); imageTintList = Draw.tint(color) }
                row.findViewById<TextView>(R.id.fixed_name).text = f.name
                row.findViewById<TextView>(R.id.fixed_cat).text = EmiText.status(f, Cycles.containing(h.today(), payday), payday)
                row.findViewById<TextView>(R.id.fixed_amount).text = Fmt.money(f.amountMinor)
                row.findViewById<View>(R.id.fixed_delete).apply { contentDescription = "Remove ${f.name}"; setOnClickListener { fixed.remove(f); refreshList() } }
                list.addView(row)
            }
            refreshTotals()
        }
        savings.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) { error.text = ""; refreshTotals() }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        income.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) { error.text = ""; refreshTotals() }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        val incomeList = v.findViewById<LinearLayout>(R.id.income_list)
        fun refreshIncomes() {
            incomeList.removeAllViews()
            for (i in incomes.sortedByDescending { it.date }) {
                val row = LayoutInflater.from(ctx).inflate(R.layout.item_fixed, incomeList, false)
                val color = ctx.getColor(R.color.accent)
                row.findViewById<View>(R.id.fixed_icon_bg).background.mutate().setTint(Draw.blend(color, ctx.getColor(R.color.surface), 0.2f))
                row.findViewById<ImageView>(R.id.fixed_icon).apply { setImageResource(R.drawable.ic_plus); imageTintList = Draw.tint(color) }
                row.findViewById<TextView>(R.id.fixed_name).text = i.name
                row.findViewById<TextView>(R.id.fixed_cat).text = Fmt.dayMonth(java.time.LocalDate.parse(i.date))
                row.findViewById<TextView>(R.id.fixed_amount).text = "+" + Fmt.money(i.amountMinor)
                row.findViewById<View>(R.id.fixed_delete).apply { contentDescription = "Remove ${i.name}"; setOnClickListener { incomes.remove(i); refreshIncomes(); refreshTotals() } }
                incomeList.addView(row)
            }
            refreshTotals()
        }
        v.findViewById<View>(R.id.income_add).setOnClickListener {
            if (incomes.size >= 60) { error.text = "You can keep up to 60 one-off incomes. Remove old ones first."; return@setOnClickListener }
            IncomeDialog(h).show { incomes.add(it); refreshIncomes() }
        }
        refreshList()
        refreshIncomes()

        val paydayValue = v.findViewById<TextView>(R.id.payday_value)
        val help = v.findViewById<TextView>(R.id.payday_help)
        fun refreshPayday() {
            paydayValue.text = Fmt.ordinal(payday)
            help.text = "Paid on the ${Fmt.ordinal(payday)}. Your cycle runs from the ${Fmt.ordinal(payday)} to the day before the next payday." +
                if (payday > 28) " In shorter months it starts on the last day." else ""
        }
        refreshPayday()
        v.findViewById<View>(R.id.payday_minus).setOnClickListener { payday = if (payday == 1) 31 else payday - 1; refreshPayday(); refreshList() }
        v.findViewById<View>(R.id.payday_plus).setOnClickListener { payday = if (payday == 31) 1 else payday + 1; refreshPayday(); refreshList() }

        fun open(emi: Boolean) {
            if (fixed.size >= 30) { error.text = "You can add up to 30 fixed spends and EMIs."; return }
            FixedDialog(h).show(emi, payday) { fixed.add(it); refreshList() }
        }
        v.findViewById<View>(R.id.fixed_add).setOnClickListener { open(false) }
        v.findViewById<View>(R.id.emi_add).setOnClickListener { open(true) }
        v.findViewById<View>(R.id.budget_save).setOnClickListener {
            val salary = Fmt.parsePaise(income.text.toString())
            if (salary == null) { error.text = "Enter your monthly salary above ₹0"; return@setOnClickListener }
            val save = savingsPaise()
            if (save == null) { error.text = "Enter the savings target as an amount, or leave it empty."; return@setOnClickListener }
            h.budgets.save(owner, salary, payday, fixed, save, incomes)
            ExpenseSync.schedule(ctx)
            com.daybook.mobile.AppSignals.dataChanged(ctx)
            h.message("Budget saved on this phone.")
            h.refreshFromServer(Cycles.containing(h.today(), payday).months())
            onDone()
        }
    }
}

object EmiText {
    /** The line shown under a fixed spend: its category, or for an EMI where it stands in this cycle. */
    fun status(f: com.daybook.mobile.domain.FixedSpend, cycle: com.daybook.mobile.domain.Cycle, payday: Int): String {
        if (!f.isEmi) return f.category
        val months = f.months!!
        val word = if (payday == 1) "month" else "cycle"
        val i = f.instalmentIn(cycle)
        return when {
            i < 1 -> "EMI · starts " + (if (i == 0) "next $word" else "in ${1 - i} ${word}s") + " · ${months} × ${Fmt.money(f.amountMinor)}"
            i > months -> "EMI · finished"
            else -> "EMI · $i of $months · total ${Fmt.money(f.totalMinor)}"
        }
    }
}
