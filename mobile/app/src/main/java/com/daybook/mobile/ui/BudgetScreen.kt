package com.daybook.mobile.ui

import android.app.AlertDialog
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.TextView
import com.daybook.mobile.R
import com.daybook.mobile.data.ExpenseSync
import com.daybook.mobile.domain.Cycles
import com.daybook.mobile.domain.FixedSpend
import java.math.BigDecimal
import java.util.UUID

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
        if (existing != null) income.setText(BigDecimal.valueOf(existing.incomeMinor, 2).stripTrailingZeros().toPlainString())
        val skip = v.findViewById<TextView>(R.id.budget_skip)
        skip.visibility = if (onboarding) View.VISIBLE else View.GONE
        skip.setOnClickListener { onDone() }
        v.findViewById<View>(R.id.budget_back).setOnClickListener { onDone() }

        val totals = v.findViewById<TextView>(R.id.fixed_totals)
        val list = v.findViewById<LinearLayout>(R.id.fixed_list)
        fun refreshTotals() {
            val salary = Fmt.parsePaise(income.text.toString()) ?: 0L
            val sum = fixed.sumOf { it.amountMinor }
            if (salary > 0 && sum > salary) { totals.text = "Fixed ${Fmt.money(sum)} is more than your salary"; totals.setTextColor(ctx.getColor(R.color.bad)) }
            else {
                totals.text = if (salary > 0) "Fixed ${Fmt.money(sum)} · Spendable ${Fmt.money(salary - sum)}" else "Fixed ${Fmt.money(sum)}"
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
                row.findViewById<TextView>(R.id.fixed_cat).text = f.category
                row.findViewById<TextView>(R.id.fixed_amount).text = Fmt.money(f.amountMinor)
                row.findViewById<View>(R.id.fixed_delete).apply { contentDescription = "Remove ${f.name}"; setOnClickListener { fixed.remove(f); refreshList() } }
                list.addView(row)
            }
            refreshTotals()
        }
        income.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) { error.text = ""; refreshTotals() }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        refreshList()

        val paydayValue = v.findViewById<TextView>(R.id.payday_value)
        val help = v.findViewById<TextView>(R.id.payday_help)
        fun refreshPayday() {
            paydayValue.text = Fmt.ordinal(payday)
            help.text = "Paid on the ${Fmt.ordinal(payday)}. Your cycle runs from the ${Fmt.ordinal(payday)} to the day before the next payday." +
                if (payday > 28) " In shorter months it starts on the last day." else ""
        }
        refreshPayday()
        v.findViewById<View>(R.id.payday_minus).setOnClickListener { payday = if (payday == 1) 31 else payday - 1; refreshPayday() }
        v.findViewById<View>(R.id.payday_plus).setOnClickListener { payday = if (payday == 31) 1 else payday + 1; refreshPayday() }

        v.findViewById<View>(R.id.fixed_add).setOnClickListener {
            if (fixed.size >= 30) { error.text = "You can add up to 30 fixed spends."; return@setOnClickListener }
            addDialog { fixed.add(it); refreshList() }
        }
        v.findViewById<View>(R.id.budget_save).setOnClickListener {
            val salary = Fmt.parsePaise(income.text.toString())
            if (salary == null) { error.text = "Enter your monthly salary above ₹0"; return@setOnClickListener }
            h.budgets.save(owner, salary, payday, fixed)
            ExpenseSync.schedule(ctx)
            h.message("Budget saved on this phone.")
            h.refreshFromServer(Cycles.containing(h.today(), payday).months())
            onDone()
        }
    }

    private fun addDialog(onAdd: (FixedSpend) -> Unit) {
        val ctx = h.context
        val view = LayoutInflater.from(ctx).inflate(R.layout.dialog_fixed, null)
        val dialog = AlertDialog.Builder(ctx, R.style.DaybookDialog).setView(view).create()
        val name = view.findViewById<EditText>(R.id.fixed_name_input)
        val amount = view.findViewById<EditText>(R.id.fixed_amount_input)
        val error = view.findViewById<TextView>(R.id.fixed_error)
        var category = "Bills"
        val grid = view.findViewById<GridLayout>(R.id.fixed_categories)
        val chips = HashMap<String, TextView>()
        fun style() {
            for ((n, chip) in chips) {
                val color = ctx.getColor(Categories.of(n).colorRes)
                if (n == category) chip.background = Draw.threeD(ctx, Draw.blend(color, ctx.getColor(R.color.surface), 0.25f), color, 14) else chip.setBackgroundResource(R.drawable.chip_3d)
                chip.isSelected = n == category
            }
        }
        for (meta in Categories.all) {
            val chip = LayoutInflater.from(ctx).inflate(R.layout.item_chip, grid, false) as TextView
            chip.text = meta.name
            chip.setCompoundDrawablesRelativeWithIntrinsicBounds(meta.icon, 0, 0, 0)
            chip.compoundDrawableTintList = Draw.tint(ctx.getColor(meta.colorRes))
            chip.setOnClickListener { category = meta.name; style() }
            chips[meta.name] = chip; grid.addView(chip)
        }
        style()
        view.findViewById<View>(R.id.fixed_cancel).setOnClickListener { dialog.dismiss() }
        view.findViewById<View>(R.id.fixed_confirm).setOnClickListener {
            val label = name.text.toString().trim()
            val paise = Fmt.parsePaise(amount.text.toString())
            when {
                label.isEmpty() -> error.text = "Give it a name, for example Rent."
                paise == null -> error.text = "Enter an amount above ₹0."
                else -> { onAdd(FixedSpend(UUID.randomUUID().toString(), label.take(40), paise, category)); dialog.dismiss() }
            }
        }
        dialog.show()
    }
}
