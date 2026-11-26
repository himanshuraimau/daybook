package com.daybook.mobile.ui

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.GridLayout
import android.widget.TextView
import com.daybook.mobile.R
import com.daybook.mobile.data.ExpenseSync
import com.daybook.mobile.domain.QuickPicks
import android.widget.LinearLayout
import com.daybook.mobile.data.ExpenseStore
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth

class EntryScreen(private val h: Host) {
    fun show(clientId: String?) {
        val v = h.inflate(R.layout.screen_entry, "entry")
        val ctx = h.context
        val owner = h.account.ownerId
        val existing = clientId?.let { h.store.one(owner, it) }
        val today = h.today()
        v.findViewById<TextView>(R.id.entry_title).text = if (existing == null) "Add expense" else "Edit expense"
        val amountView = v.findViewById<TextView>(R.id.entry_amount)
        val note = v.findViewById<EditText>(R.id.entry_note)
        val error = v.findViewById<TextView>(R.id.entry_error)
        val digits = StringBuilder()
        if (existing != null) { digits.append(BigDecimal.valueOf(existing.amountMinor, 2).stripTrailingZeros().toPlainString()); note.setText(existing.note) }
        var chosenDate: LocalDate = existing?.let { LocalDate.parse(it.occurredOn) } ?: today
        var chosenCategory: String = existing?.category ?: Categories.all.first().name

        fun showAmount() {
            val text = digits.toString()
            val whole = text.substringBefore('.')
            val grouped = if (whole.isEmpty()) "0" else Fmt.indian(whole)
            amountView.text = "₹" + grouped + (if (text.contains('.')) "." + text.substringAfter('.') else "")
            amountView.contentDescription = if (text.isEmpty()) "Amount, zero rupees" else "Amount, ${amountView.text}"
        }
        fun key(k: String) {
            error.text = ""
            val s = digits.toString()
            when (k) {
                "." -> if (!s.contains('.')) digits.append(if (s.isEmpty()) "0." else ".")
                else -> {
                    if (s.contains('.') && s.substringAfter('.').length >= 2) return
                    if (!s.contains('.') && s.length >= 10) return
                    if (s == "0") digits.setLength(0)
                    digits.append(k)
                }
            }
            showAmount()
        }
        for (k in listOf("0", "1", "2", "3", "4", "5", "6", "7", "8", "9")) v.findViewById<View>(ctx.resources.getIdentifier("key_$k", "id", ctx.packageName)).setOnClickListener { key(k) }
        v.findViewById<View>(R.id.key_dot).setOnClickListener { key(".") }
        v.findViewById<View>(R.id.key_back).setOnClickListener { error.text = ""; if (digits.isNotEmpty()) digits.setLength(digits.length - 1); showAmount() }
        showAmount()

        // Category chips
        val grid = v.findViewById<GridLayout>(R.id.entry_categories)
        val chips = HashMap<String, TextView>()
        fun styleCategories() {
            for ((name, chip) in chips) {
                val meta = Categories.of(name); val color = ctx.getColor(meta.colorRes)
                if (name == chosenCategory) chip.background = Draw.threeD(ctx, Draw.blend(color, ctx.getColor(R.color.surface), 0.25f), color, 14)
                else chip.setBackgroundResource(R.drawable.chip_3d)
                chip.isSelected = name == chosenCategory
            }
        }
        for (meta in Categories.all) {
            val chip = LayoutInflater.from(ctx).inflate(R.layout.item_chip, grid, false) as TextView
            chip.text = meta.chipLabel
            chip.setCompoundDrawablesRelativeWithIntrinsicBounds(meta.icon, 0, 0, 0)
            chip.compoundDrawableTintList = Draw.tint(ctx.getColor(meta.colorRes))
            chip.contentDescription = "Category ${meta.name}"
            chip.setOnClickListener { chosenCategory = meta.name; styleCategories() }
            chips[meta.name] = chip; grid.addView(chip)
        }
        styleCategories()

        // Date chips
        val tChip = v.findViewById<TextView>(R.id.entry_date_today)
        val yChip = v.findViewById<TextView>(R.id.entry_date_yesterday)
        val pChip = v.findViewById<TextView>(R.id.entry_date_pick)
        fun styleDates() {
            val accent = ctx.getColor(R.color.accent)
            val pickSelected = chosenDate != today && chosenDate != today.minusDays(1)
            pChip.text = if (pickSelected) Fmt.dayMonth(chosenDate) else "Pick date"
            tChip.contentDescription = "Date: Today"; yChip.contentDescription = "Date: Yesterday"; pChip.contentDescription = "Date: ${if (pickSelected) Fmt.dayMonth(chosenDate) else "pick a date"}"
            for ((chip, selected) in listOf(tChip to (chosenDate == today), yChip to (chosenDate == today.minusDays(1)), pChip to pickSelected)) {
                if (selected) chip.background = Draw.threeD(ctx, Draw.blend(accent, ctx.getColor(R.color.surface), 0.22f), accent, 14)
                else chip.setBackgroundResource(R.drawable.chip_3d)
                chip.isSelected = selected
            }
        }
        tChip.setOnClickListener { chosenDate = today; styleDates() }
        yChip.setOnClickListener { chosenDate = today.minusDays(1); styleDates() }
        pChip.setOnClickListener {
            DatePickerDialog(ctx, { _, y, m, d -> chosenDate = LocalDate.of(y, m + 1, d); styleDates() }, chosenDate.year, chosenDate.monthValue - 1, chosenDate.dayOfMonth).show()
        }
        styleDates()

        // Quick picks: repeat the last expense, or one of the amounts you enter most often.
        if (existing == null) {
            val picks = QuickPicks.from(h.store.all(owner), today)
            if (picks.isNotEmpty()) {
                val row = v.findViewById<LinearLayout>(R.id.entry_picks)
                v.findViewById<View>(R.id.entry_picks_label).visibility = View.VISIBLE
                v.findViewById<View>(R.id.entry_picks_scroll).visibility = View.VISIBLE
                for (pick in picks) {
                    val title = pick.note.ifBlank { pick.category }.let { if (it.length > 14) it.take(13) + "…" else it }
                    val chip = TextView(ctx).apply {
                        text = (if (pick.isRepeatOfLast) "Repeat · " else "") + title + " " + Fmt.money(pick.amountMinor)
                        setTextAppearance(R.style.Text_Body); textSize = 13f; setTypeface(typeface, android.graphics.Typeface.BOLD)
                        gravity = android.view.Gravity.CENTER; minHeight = Draw.dp(ctx, 44)
                        setPadding(Draw.dp(ctx, 14), 0, Draw.dp(ctx, 14), Draw.dp(ctx, 4)); setBackgroundResource(R.drawable.chip_3d)
                        isClickable = true; isFocusable = true
                        contentDescription = "Use ${pick.category}, $title, ${Fmt.money(pick.amountMinor)}"
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = Draw.dp(ctx, 6) }
                        setOnClickListener {
                            digits.setLength(0); digits.append(BigDecimal.valueOf(pick.amountMinor, 2).stripTrailingZeros().toPlainString())
                            error.text = ""; showAmount()
                            chosenCategory = pick.category; styleCategories()
                            note.setText(pick.note)
                        }
                    }
                    row.addView(chip)
                }
            }
        }

        if (existing?.status == "failed") {
            if (existing.serverId == null) error.text = "Couldn't sync. Your expense is saved here. Edit and save, or retry from Home."
            else {
                error.text = "Couldn't sync. Tap here to load the server copy before editing again."
                error.setOnClickListener {
                    AlertDialog.Builder(ctx, R.style.DaybookDialog).setTitle("Load server copy?")
                        .setMessage("This will discard the unsynced changes saved on this phone.")
                        .setNegativeButton("Cancel", null).setPositiveButton("Load") { _, _ ->
                            h.perform({ val fresh = h.api.get(h.account, existing.serverId); h.store.acceptServerCopy(owner, fresh)
                                h.runOnUi { h.message("Server copy loaded"); h.returnFromEntry() }
                            }, { reason -> error.text = reason })
                        }.show()
                }
            }
        }

        val emiLink = v.findViewById<View>(R.id.entry_emi_link)
        emiLink.visibility = if (existing == null) View.VISIBLE else View.GONE
        emiLink.setOnClickListener {
            val profile = h.budgets.get(owner)
            if (profile == null) { h.message("Set your salary first, then add the EMI."); h.showBudget(false); return@setOnClickListener }
            FixedDialog(h).show(true, profile.payday, FixedDialog.Prefill(note.text.toString().trim(), chosenCategory, Fmt.parsePaise(digits.toString()))) { emi ->
                h.budgets.save(owner, profile.copy(fixed = profile.fixed + emi))
                ExpenseSync.schedule(ctx); com.daybook.mobile.AppSignals.dataChanged(ctx)
                h.message("EMI added. It is set aside every cycle, so you don't need to log it.")
                h.refreshFromServer(); h.showHome()
            }
        }
        v.findViewById<View>(R.id.entry_back).setOnClickListener { h.returnFromEntry() }
        val save = v.findViewById<TextView>(R.id.entry_save)
        save.setOnClickListener {
            val minor = Fmt.parsePaise(digits.toString())
            val noteValue = note.text.toString().trim()
            if (minor == null) { error.text = "Enter an amount above ₹0"; return@setOnClickListener }
            if (noteValue.length > 240) { error.text = "Keep the note under 240 characters"; return@setOnClickListener }
            val date = chosenDate.toString()
            val latest = existing?.let { h.store.one(owner, it.clientId) }
            if (existing == null) {
                val firstToday = date == today.toString() && h.store.all(owner).none { it.occurredOn == date }
                h.store.add(owner, minor, chosenCategory, date, noteValue)
                h.flamePop = firstToday
                h.message("Saved on this phone. Waiting to sync.")
                ExpenseSync.schedule(ctx); com.daybook.mobile.AppSignals.dataChanged(ctx); h.showHome(); h.refreshFromServer(listOf(YearMonth.from(chosenDate), YearMonth.now()))
            } else if (latest != null && latest.status != "synced") {
                if (h.store.changePending(owner, latest.clientId, minor, chosenCategory, date, noteValue)) {
                    h.message("Saved on this phone. Waiting to sync.")
                    ExpenseSync.schedule(ctx); com.daybook.mobile.AppSignals.dataChanged(ctx); h.returnFromEntry()
                } else error.text = "This expense changed. Reopen it and try again."
            } else {
                save.isEnabled = false
                val changed = (latest ?: existing).copy(amountMinor = minor, category = chosenCategory, occurredOn = date, note = noteValue)
                h.perform({ val updated = h.api.update(h.account, changed); h.store.replaceSynced(owner, updated)
                    h.runOnUi { h.message("Expense updated"); h.returnFromEntry() }
                }, { reason -> save.isEnabled = true; error.text = reason })
            }
        }
        if (existing != null) v.findViewById<TextView>(R.id.entry_delete).apply {
            visibility = View.VISIBLE
            setOnClickListener {
                AlertDialog.Builder(ctx, R.style.DaybookDialog).setTitle("Delete expense?").setMessage("This entry will be removed.")
                    .setNegativeButton("Cancel", null).setPositiveButton("Delete") { _, _ ->
                        val current = h.store.one(owner, existing.clientId)
                        if (current != null && current.status != "synced") { h.store.deletePending(owner, current.clientId); ExpenseSync.schedule(ctx); h.returnFromEntry() }
                        else if (current != null) h.perform({ h.api.delete(h.account, current); h.store.deleteSynced(owner, current.clientId)
                            h.runOnUi { h.returnFromEntry() }
                        }, { reason -> error.text = reason })
                    }.show()
            }
        }
    }
}
