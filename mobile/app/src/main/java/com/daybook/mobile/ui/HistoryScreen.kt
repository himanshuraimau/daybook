package com.daybook.mobile.ui

import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.daybook.mobile.R
import com.daybook.mobile.domain.Cycle
import com.daybook.mobile.domain.Cycles
import java.time.LocalDate

class HistoryScreen(private val h: Host) {
    fun show(fetch: Boolean) {
        val v = h.inflate(R.layout.screen_history, "history")
        val ctx = h.context
        val owner = h.account.ownerId
        val payday = h.budgets.get(owner)?.payday ?: 1
        var cycle: Cycle = Cycles.containing(h.today(), payday)
        repeat(-h.historyOffset) { cycle = Cycles.previous(cycle, payday) }
        v.findViewById<TextView>(R.id.history_cycle).text = Fmt.range(cycle, payday)
        v.findViewById<View>(R.id.prev_cycle).setOnClickListener { h.historyOffset -= 1; h.showHistory() }
        val next = v.findViewById<View>(R.id.next_cycle)
        next.alpha = if (h.historyOffset < 0) 1f else 0.35f
        next.isEnabled = h.historyOffset < 0
        next.setOnClickListener { if (h.historyOffset < 0) { h.historyOffset += 1; h.showHistory() } }
        val items = h.store.all(owner).filter { LocalDate.parse(it.occurredOn) in cycle }
        v.findViewById<TextView>(R.id.history_total).text = if (items.isEmpty()) "" else "Spent ${Fmt.money(items.sumOf { it.amountMinor })} · ${items.size} ${if (items.size == 1) "expense" else "expenses"}"
        val list = v.findViewById<LinearLayout>(R.id.history_list)
        if (items.isEmpty()) list.addView(TextView(ctx).apply {
            text = "No expenses in this ${if (payday == 1) "month" else "cycle"}."; setTextAppearance(R.style.Text_Body); setTextColor(ctx.getColor(R.color.muted)); gravity = android.view.Gravity.CENTER; setPadding(0, Draw.dp(ctx, 40), 0, 0)
        })
        for ((day, entries) in items.groupBy { it.occurredOn }) {
            val date = LocalDate.parse(day)
            val header = LayoutInflater.from(ctx).inflate(R.layout.item_day_header, list, false)
            header.findViewById<TextView>(R.id.day_label).text = Fmt.day(date)
            header.findViewById<TextView>(R.id.day_total).text = Fmt.money(entries.sumOf { it.amountMinor })
            list.addView(header)
            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL; setBackgroundResource(R.drawable.card_3d)
                setPadding(Draw.dp(ctx, 14), Draw.dp(ctx, 4), Draw.dp(ctx, 14), Draw.dp(ctx, 9))
            }
            entries.forEach { e -> card.addView(ExpenseRow.bind(ctx, card, e) { ExpenseActions.open(h, e) }) }
            list.addView(card)
        }
        if (fetch) h.refreshFromServer(cycle.months())
    }
}
