package com.daybook.mobile.ui

import android.animation.ValueAnimator
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.daybook.mobile.R
import com.daybook.mobile.data.ExpenseSync
import com.daybook.mobile.domain.Cycles
import com.daybook.mobile.domain.Insights
import com.daybook.mobile.domain.Streak
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

class HomeScreen(private val h: Host) {
    fun show() {
        val v = h.inflate(R.layout.screen_home, "home")
        val ctx = h.context
        val owner = h.account.ownerId
        val all = h.store.all(owner)
        val profile = h.budgets.get(owner)
        val today = h.today()
        val payday = profile?.payday ?: 1
        val cycle = Cycles.containing(today, payday)
        val sum = Insights.summarize(all, profile, today, cycle)
        v.findViewById<TextView>(R.id.home_avatar).text = h.account.email.firstOrNull()?.uppercase() ?: "D"

        // Streak
        val logged = all.map { LocalDate.parse(it.occurredOn) }.toSet()
        val streak = Streak.current(logged, today)
        v.findViewById<TextView>(R.id.streak_title).text = when (streak) { 0 -> "Start your streak"; 1 -> "1 day in a row"; else -> "$streak days in a row" }
        v.findViewById<TextView>(R.id.streak_sub).text = when {
            streak == 0 -> "Log an expense today to begin"
            today in logged -> "Today is logged. Nice."
            else -> "Log something today to keep it going"
        }
        val week = v.findViewById<LinearLayout>(R.id.streak_week)
        for ((date, did) in Streak.week(logged, today)) {
            val d = LayoutInflater.from(ctx).inflate(R.layout.item_streak_day, week, false)
            val isToday = date == today
            d.findViewById<TextView>(R.id.sd_label).text = date.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.ENGLISH)
            d.findViewById<View>(R.id.sd_dot).setBackgroundResource(if (did) (if (isToday) R.drawable.dot_on_today else R.drawable.dot_on) else (if (isToday) R.drawable.dot_off_today else R.drawable.dot_off))
            d.findViewById<View>(R.id.sd_check).visibility = if (did) View.VISIBLE else View.GONE
            d.contentDescription = "${Fmt.day(date)}, ${if (did) "logged" else "nothing logged"}"
            week.addView(d)
        }
        if (h.flamePop) {
            h.flamePop = false
            val flame = v.findViewById<ImageView>(R.id.streak_flame)
            if (ValueAnimator.areAnimatorsEnabled()) flame.animate().scaleX(1.5f).scaleY(1.5f).setDuration(160).withEndAction { flame.animate().scaleX(1f).scaleY(1f).setDuration(160).start() }.start()
        }

        // Hero
        val b = sum.budget
        val hero = v.findViewById<LinearLayout>(R.id.hero)
        val label = v.findViewById<TextView>(R.id.hero_label)
        val amount = v.findViewById<TextView>(R.id.hero_amount)
        val chip1 = v.findViewById<TextView>(R.id.hero_chip1)
        val chip2 = v.findViewById<TextView>(R.id.hero_chip2)
        val over = b?.over == true
        if (b != null) {
            label.text = "Left to spend · ${Fmt.range(cycle, payday)}"
            amount.text = if (over) "Over by ${Fmt.money(-b.leftMinor)}" else Fmt.money(b.leftMinor)
            chip1.text = when {
                over -> "Over budget"
                b.daysLeft > 0 -> "${Fmt.money(b.dailyAllowanceMinor)} a day · ${b.daysLeft} ${if (b.daysLeft == 1) "day" else "days"} left"
                else -> "This cycle has ended"
            }
        } else {
            label.text = "Spent today"
            amount.text = Fmt.money(sum.todayMinor)
            chip1.text = "This ${if (payday == 1) "month" else "cycle"} ${Fmt.money(sum.spentMinor)}"
        }
        chip1.visibility = View.VISIBLE
        val onHero = ctx.getColor(if (over) R.color.on_bad else R.color.on_accent)
        hero.setBackgroundResource(if (over) R.drawable.card_hero_bad else R.drawable.card_hero)
        for (t in listOf(label, amount, chip1, chip2)) t.setTextColor(onHero)
        val waiting = h.store.unsyncedCount(owner)
        chip2.visibility = if (waiting > 0) View.VISIBLE else View.GONE
        chip2.text = "$waiting waiting to sync · Retry"
        chip2.setOnClickListener { h.store.retryFailed(owner); ExpenseSync.schedule(ctx); h.refreshFromServer(cycle.months()) }

        // Prompt, today row, budget nudge
        v.findViewById<View>(R.id.open_prompt).visibility = if (h.promptVisible) View.VISIBLE else View.GONE
        v.findViewById<View>(R.id.prompt_add).setOnClickListener { h.promptVisible = false; h.showEntry() }
        v.findViewById<View>(R.id.prompt_dismiss).setOnClickListener { h.promptVisible = false; v.findViewById<View>(R.id.open_prompt).visibility = View.GONE; v.findViewById<View>(R.id.home_add).visibility = View.VISIBLE }
        v.findViewById<View>(R.id.home_add).apply { visibility = if (h.promptVisible) View.GONE else View.VISIBLE; setOnClickListener { h.showEntry() } }
        val todayRow = v.findViewById<View>(R.id.today_row)
        todayRow.visibility = if (b != null) View.VISIBLE else View.GONE
        if (b != null) {
            val n = all.count { it.occurredOn == today.toString() }
            v.findViewById<TextView>(R.id.today_label).text = "Today · $n ${if (n == 1) "entry" else "entries"}"
            v.findViewById<TextView>(R.id.today_value).text = Fmt.money(sum.todayMinor)
        }
        v.findViewById<View>(R.id.budget_nudge).visibility = if (profile == null) View.VISIBLE else View.GONE
        v.findViewById<View>(R.id.nudge_button).setOnClickListener { h.showBudget(false) }

        // Where it went + recent
        val cats = v.findViewById<LinearLayout>(R.id.categories)
        v.findViewById<View>(R.id.see_all_insights).setOnClickListener { h.showInsights() }
        if (sum.categories.isEmpty()) cats.addView(TextView(ctx).apply { text = "Add your first expense today"; setTextAppearance(R.style.Text_Body); setTextColor(ctx.getColor(R.color.muted)); setPadding(0, Draw.dp(ctx, 8), 0, Draw.dp(ctx, 8)) })
        val top = sum.categories.firstOrNull()?.amountMinor ?: 1L
        for (c in sum.categories.take(3)) RankRow.add(ctx, cats, c.category, Fmt.money(c.amountMinor), c.amountMinor.toFloat() / top, Categories.of(c.category).colorRes)
        val recent = v.findViewById<LinearLayout>(R.id.recent)
        if (all.isEmpty()) recent.addView(TextView(ctx).apply { text = "Nothing recorded yet. Add an expense to start your daybook."; setTextAppearance(R.style.Text_Body); setTextColor(ctx.getColor(R.color.muted)); setPadding(0, Draw.dp(ctx, 12), 0, Draw.dp(ctx, 8)) })
        all.take(3).forEach { e -> recent.addView(ExpenseRow.bind(ctx, recent, e) { ExpenseActions.open(h, e) }) }
    }
}
