package com.daybook.mobile.ui

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.TextView
import com.daybook.mobile.R
import com.daybook.mobile.domain.OneOffIncome
import java.time.LocalDate
import java.util.UUID

/** Dialog for recording a one-off payment such as a bonus. */
class IncomeDialog(private val h: Host) {
    fun show(onAdd: (OneOffIncome) -> Unit) {
        val ctx = h.context
        val view = LayoutInflater.from(ctx).inflate(R.layout.dialog_income, null)
        val dialog = AlertDialog.Builder(ctx, R.style.DaybookDialog).setView(view).setPositiveButton("Add", null).setNegativeButton("Cancel", null).create()
        val name = view.findViewById<EditText>(R.id.income_name_input)
        val amount = view.findViewById<EditText>(R.id.income_amount_input)
        val error = view.findViewById<TextView>(R.id.income_error)
        val today = h.today()
        var chosen: LocalDate = today
        val tChip = view.findViewById<TextView>(R.id.income_date_today)
        val yChip = view.findViewById<TextView>(R.id.income_date_yesterday)
        val pChip = view.findViewById<TextView>(R.id.income_date_pick)
        fun styleDates() {
            val accent = ctx.getColor(R.color.accent)
            val pick = chosen != today && chosen != today.minusDays(1)
            pChip.text = if (pick) Fmt.dayMonth(chosen) else "Pick date"
            for ((chip, on) in listOf(tChip to (chosen == today), yChip to (chosen == today.minusDays(1)), pChip to pick)) {
                if (on) chip.background = Draw.threeD(ctx, Draw.blend(accent, ctx.getColor(R.color.surface), 0.22f), accent, 14) else chip.setBackgroundResource(R.drawable.chip_3d)
                chip.isSelected = on
            }
        }
        tChip.setOnClickListener { chosen = today; styleDates() }
        yChip.setOnClickListener { chosen = today.minusDays(1); styleDates() }
        pChip.setOnClickListener { DatePickerDialog(ctx, { _, y, m, d -> chosen = LocalDate.of(y, m + 1, d); styleDates() }, chosen.year, chosen.monthValue - 1, chosen.dayOfMonth).show() }
        styleDates()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val label = name.text.toString().trim()
            val paise = Fmt.parsePaise(amount.text.toString())
            when {
                label.isEmpty() -> error.text = "Give it a name, for example Diwali bonus."
                paise == null -> error.text = "Enter an amount above ₹0."
                else -> { onAdd(OneOffIncome(UUID.randomUUID().toString(), label.take(40), paise, chosen.toString())); dialog.dismiss() }
            }
        }
    }
}
