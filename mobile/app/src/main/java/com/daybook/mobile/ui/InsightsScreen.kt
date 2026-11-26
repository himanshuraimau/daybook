package com.daybook.mobile.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.daybook.mobile.R
import com.daybook.mobile.domain.CycleSummary
import com.daybook.mobile.domain.Cycles
import com.daybook.mobile.domain.Insights

class InsightsScreen(private val h: Host) {
    fun show() {
        val v = h.inflate(R.layout.screen_insights, "insights")
        val ctx = h.context
        val owner = h.account.ownerId
        val profile = h.budgets.get(owner)
        val payday = profile?.payday ?: 1
        val today = h.today()
        val current = Cycles.containing(today, payday)
        val cycle = if (h.insightsLast) Cycles.previous(current, payday) else current
        val all = h.store.all(owner)
        val s = Insights.summarize(all, profile, today, cycle, Cycles.previous(cycle, payday))
        val word = if (payday == 1) "month" else "cycle"

        val segCurrent = v.findViewById<TextView>(R.id.seg_current)
        val segLast = v.findViewById<TextView>(R.id.seg_last)
        segCurrent.text = "This $word"; segLast.text = "Last $word"
        fun styleSeg() {
            for ((t, on) in listOf(segCurrent to !h.insightsLast, segLast to h.insightsLast)) {
                if (on) t.setBackgroundResource(R.drawable.segment_on) else t.background = null
                t.setTextColor(ctx.getColor(if (on) R.color.bg else R.color.muted)); t.isSelected = on
            }
        }
        styleSeg()
        segCurrent.setOnClickListener { if (h.insightsLast) { h.insightsLast = false; h.showInsights() } }
        segLast.setOnClickListener { if (!h.insightsLast) { h.insightsLast = true; h.showInsights() } }
        v.findViewById<TextView>(R.id.ins_range).text = Fmt.range(cycle, payday)

        val strip = v.findViewById<LinearLayout>(R.id.ins_strip)
        val b = s.budget
        stat(ctx, strip, "Spent", Fmt.money(s.spentMinor), "${s.elapsedDays} of ${cycle.days} days")
        if (b != null) {
            stat(ctx, strip, "Fixed", Fmt.money(b.fixedMinor), if (b.savingsMinor > 0) "Saving ${Fmt.money(b.savingsMinor)} too" else "Set aside")
            stat(ctx, strip, if (b.over) "Over by" else "Left", Fmt.money(Math.abs(b.leftMinor)), if (b.over) "Over budget" else "Of ${Fmt.money(b.spendableMinor)}" + (if (b.extraIncomeMinor > 0) " incl. ${Fmt.money(b.extraIncomeMinor)} extra" else ""), if (b.over) R.color.bad else null)
        }

        val hasData = s.spentMinor > 0
        v.findViewById<View>(R.id.ins_empty).visibility = if (hasData) View.GONE else View.VISIBLE
        for (id in listOf(R.id.ins_bars_card, R.id.ins_pace_card, R.id.ins_categories, R.id.ins_grid1, R.id.ins_grid2)) v.findViewById<View>(id).visibility = if (hasData) View.VISIBLE else View.GONE
        if (!hasData) { hideHeadings(v); return }

        bars(v, s, payday)
        val pace = v.findViewById<View>(R.id.ins_pace_card)
        if (b == null) pace.visibility = View.GONE else pace(v, s)
        val cats = v.findViewById<LinearLayout>(R.id.ins_categories)
        val total = s.spentMinor.coerceAtLeast(1)
        val top = s.categories.first().amountMinor
        for (c in s.categories) RankRow.add(ctx, cats, "${c.category} · ${Math.round(c.amountMinor * 100.0 / total)}%", Fmt.money(c.amountMinor), c.amountMinor.toFloat() / top, Categories.of(c.category).colorRes)

        val g1 = v.findViewById<LinearLayout>(R.id.ins_grid1)
        val g2 = v.findViewById<LinearLayout>(R.id.ins_grid2)
        stat(ctx, g1, "Average a day", Fmt.money(s.averagePerDayMinor), "Over ${s.elapsedDays} ${if (s.elapsedDays == 1) "day" else "days"}")
        val big = s.biggest!!
        stat(ctx, g1, "Biggest spend", Fmt.money(big.amountMinor), "${big.note.ifBlank { big.category }} · ${Fmt.dayMonth(java.time.LocalDate.parse(big.occurredOn))}")
        stat(ctx, g2, "No-spend days", s.noSpendDays.toString(), "Of ${s.elapsedDays} ${if (s.elapsedDays == 1) "day" else "days"}")
        val vs = s.vsPreviousPercent
        stat(ctx, g2, "Vs last $word", if (vs == null) "—" else (if (vs > 0) "+$vs%" else if (vs < 0) "−${-vs}%" else "0%"), if (vs == null) "No earlier spending to compare" else "Up to the same day", if (vs != null && vs > 0) R.color.bad else null)
    }

    private fun hideHeadings(v: View) {
        // The four section headings sit between the cards; hide them with their cards when there is nothing to show.
        val root = v.findViewById<LinearLayout>(R.id.ins_strip).parent as ViewGroup
        for (i in 0 until root.childCount) {
            val c = root.getChildAt(i)
            if (c is TextView && c.id == View.NO_ID && (c.text == "Daily spending" || c.text == "Where it went" || c.text == "The numbers")) c.visibility = View.GONE
        }
    }

    private fun stat(ctx: android.content.Context, parent: LinearLayout, label: String, value: String, sub: String, colorRes: Int? = null) {
        val v = LayoutInflater.from(ctx).inflate(R.layout.item_stat, parent, false)
        v.findViewById<TextView>(R.id.stat_label).text = label
        v.findViewById<TextView>(R.id.stat_value).apply { text = value; if (colorRes != null) setTextColor(ctx.getColor(colorRes)) }
        v.findViewById<TextView>(R.id.stat_sub).text = sub
        v.contentDescription = "$label: $value. $sub"
        parent.addView(v)
    }

    private fun bars(v: View, s: CycleSummary, payday: Int) {
        val view = v.findViewById<DailyBarsView>(R.id.ins_bars)
        val days = (0 until s.cycle.days).map { s.cycle.start.plusDays(it.toLong()) }
        val amounts = days.map { s.perDay[it] ?: 0L }
        val todayIndex = days.indexOf(h.today())
        val perDay = s.budget?.let { if (it.spendableMinor > 0) it.spendableMinor / s.cycle.days else null }
        view.setData(amounts, todayIndex, perDay, days.first().dayOfMonth.toString(), days[days.size / 2].dayOfMonth.toString(), days.last().dayOfMonth.toString())
        val caption = v.findViewById<TextView>(R.id.ins_bars_caption)
        val base = if (perDay != null) "Dashed line: ${Fmt.money(perDay)} a day. Tap a bar for that day." else "Tap a bar for that day."
        caption.text = base
        view.contentDescription = "Daily spending bars for ${Fmt.range(s.cycle, payday)}. Total ${Fmt.money(s.spentMinor)}."
        view.onSelect = { i -> caption.text = "${Fmt.day(days[i])}: ${if (amounts[i] == 0L) "nothing spent" else Fmt.money(amounts[i])}" }
    }

    private fun pace(v: View, s: CycleSummary) {
        val b = s.budget!!
        val text = v.findViewById<TextView>(R.id.ins_pace_text)
        val over = b.projectedFinishMinor < 0
        val ended = h.today().isAfter(s.cycle.end)
        text.text = when {
            s.elapsedDays == 0 -> "Add expenses to see your pace."
            ended -> if (b.over) "You finished ${Fmt.money(-b.leftMinor)} over budget." else "You finished ${Fmt.money(b.leftMinor)} under budget."
            over -> "At this pace you'll finish ${Fmt.money(-b.projectedFinishMinor)} over budget."
            else -> "At this pace you'll finish ${Fmt.money(b.projectedFinishMinor)} under budget."
        }
        text.setTextColor(h.context.getColor(if (s.elapsedDays > 0 && (if (ended) b.over else over)) R.color.bad else R.color.ink))
        val bar = v.findViewById<View>(R.id.ins_pace_bar)
        val used = b.usedPercent.coerceIn(0, 100)
        bar.background = android.graphics.drawable.GradientDrawable().apply { setColor(h.context.getColor(if (b.usedPercent > 100) R.color.bad else R.color.accent)); cornerRadius = Draw.dp(h.context, 6).toFloat() }
        (bar.layoutParams as LinearLayout.LayoutParams).weight = used.toFloat()
        v.findViewById<TextView>(R.id.ins_pace_caption).text = "${b.usedPercent}% of spendable used · ${s.elapsedDays} of ${s.cycle.days} days"
    }
}
