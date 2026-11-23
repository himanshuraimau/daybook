package com.daybook.mobile.ui

import android.app.AlertDialog
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.GridLayout
import android.widget.TextView
import com.daybook.mobile.R
import com.daybook.mobile.domain.Cycles
import com.daybook.mobile.domain.FixedSpend
import java.math.BigDecimal
import java.time.YearMonth
import java.util.UUID

/** Dialog for adding a fixed monthly spend or an EMI (a fixed spend with a number of months). */
class FixedDialog(private val h: Host) {
    class Prefill(val name: String = "", val category: String = "Bills", val instalmentPaise: Long? = null)

    fun show(emi: Boolean, payday: Int, prefill: Prefill = Prefill(), onAdd: (FixedSpend) -> Unit) {
        val ctx = h.context
        val view = LayoutInflater.from(ctx).inflate(R.layout.dialog_fixed, null)
        val dialog = AlertDialog.Builder(ctx, R.style.DaybookDialog).setView(view).setPositiveButton("Add", null).setNegativeButton("Cancel", null).create()
        val name = view.findViewById<EditText>(R.id.fixed_name_input)
        val amount = view.findViewById<EditText>(R.id.fixed_amount_input)
        val months = view.findViewById<EditText>(R.id.fixed_months_input)
        val total = view.findViewById<EditText>(R.id.fixed_total_input)
        val paid = view.findViewById<EditText>(R.id.fixed_paid_input)
        val summary = view.findViewById<TextView>(R.id.fixed_summary)
        val error = view.findViewById<TextView>(R.id.fixed_error)
        view.findViewById<TextView>(R.id.fixed_title).text = if (emi) "Add EMI" else "Add fixed spend"
        if (emi) {
            view.findViewById<View>(R.id.emi_group).visibility = View.VISIBLE
            name.hint = "Name, for example Phone EMI"; amount.hint = "Monthly instalment in ₹"
            view.findViewById<TextView>(R.id.fixed_amount_label).text = "Monthly instalment (₹)"
        }
        name.setText(prefill.name)
        prefill.instalmentPaise?.let { amount.setText(plain(it)) }
        var category = prefill.category
        var startNext = false

        // Instalment, months and total cost stay consistent: editing the total works out the instalment, otherwise the total follows.
        var syncing = false
        fun refresh(source: View) {
            if (syncing) return
            syncing = true
            val m = months.text.toString().toIntOrNull()?.takeIf { it > 0 }
            val inst = Fmt.parsePaise(amount.text.toString())
            val tot = Fmt.parsePaise(total.text.toString(), 10_000_000_000L)
            if (m != null) {
                if (source === total && tot != null) amount.setText(plain((tot + m / 2) / m))
                else if (inst != null) total.setText(plain(inst * m))
                else if (tot != null) amount.setText(plain((tot + m / 2) / m))
            }
            val i2 = Fmt.parsePaise(amount.text.toString())
            summary.text = if (i2 != null && m != null) "${Fmt.money(i2)} × $m ${if (m == 1) "month" else "months"} = ${Fmt.money(i2 * m)}" else ""
            error.text = ""
            syncing = false
        }
        if (emi) {
            for (field in listOf(amount, months, total)) field.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) { if (field.hasFocus() || field === months) refresh(field) }
                override fun afterTextChanged(s: Editable?) {}
            })
            refresh(amount)
        }

        // Category chips
        val grid = view.findViewById<GridLayout>(R.id.fixed_categories)
        val chips = HashMap<String, TextView>()
        fun styleCategories() {
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
            chip.setOnClickListener { category = meta.name; styleCategories() }
            chips[meta.name] = chip; grid.addView(chip)
        }
        styleCategories()

        // Start chips
        val thisChip = view.findViewById<TextView>(R.id.fixed_start_this)
        val nextChip = view.findViewById<TextView>(R.id.fixed_start_next)
        fun styleStart() {
            val accent = ctx.getColor(R.color.accent)
            for ((chip, on) in listOf(thisChip to !startNext, nextChip to startNext)) {
                if (on) chip.background = Draw.threeD(ctx, Draw.blend(accent, ctx.getColor(R.color.surface), 0.22f), accent, 14) else chip.setBackgroundResource(R.drawable.chip_3d)
                chip.isSelected = on
            }
            paid.visibility = if (startNext) View.GONE else View.VISIBLE
        }
        thisChip.setOnClickListener { startNext = false; styleStart() }
        nextChip.setOnClickListener { startNext = true; styleStart() }
        styleStart()

        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val label = name.text.toString().trim()
            val paise = Fmt.parsePaise(amount.text.toString())
            if (label.isEmpty()) { error.text = "Give it a name, for example ${if (emi) "Phone EMI" else "Rent"}."; return@setOnClickListener }
            if (paise == null) { error.text = if (emi) "Enter the monthly instalment above ₹0." else "Enter an amount above ₹0."; return@setOnClickListener }
            if (!emi) { onAdd(FixedSpend(UUID.randomUUID().toString(), label.take(40), paise, category)); dialog.dismiss(); return@setOnClickListener }
            val m = months.text.toString().toIntOrNull()
            if (m == null || m < 1 || m > 120) { error.text = "Enter the number of months, from 1 to 120."; return@setOnClickListener }
            val alreadyPaid = if (startNext) 0 else (paid.text.toString().ifBlank { "0" }.toIntOrNull() ?: -1)
            if (alreadyPaid < 0) { error.text = "Enter how many instalments are already paid, or 0."; return@setOnClickListener }
            if (alreadyPaid >= m) { error.text = "All $m instalments are already paid, so there is nothing left to set aside."; return@setOnClickListener }
            val thisKey = YearMonth.from(Cycles.containing(h.today(), payday).start)
            val start = if (startNext) thisKey.plusMonths(1) else thisKey.minusMonths(alreadyPaid.toLong())
            onAdd(FixedSpend(UUID.randomUUID().toString(), label.take(40), paise, category, m, start.toString()))
            dialog.dismiss()
        }
    }

    private fun plain(paise: Long) = BigDecimal.valueOf(paise, 2).stripTrailingZeros().toPlainString()
}
