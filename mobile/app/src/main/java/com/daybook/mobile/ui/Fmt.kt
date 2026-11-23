package com.daybook.mobile.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.daybook.mobile.R
import com.daybook.mobile.data.LocalExpense
import com.daybook.mobile.domain.Cycle
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Display formatting. Money is held as integer paise and only converted here. */
object Fmt {
    private val en = Locale.ENGLISH
    /** Groups digits the Indian way: the last three together, then pairs (7,12,34,567). */
    fun indian(digits: String): String {
        if (digits.length <= 3) return digits
        val head = digits.dropLast(3)
        val pairs = head.reversed().chunked(2).joinToString(",").reversed()
        return "$pairs,${digits.takeLast(3)}"
    }
    /** ₹ with Indian grouping; two decimals only when the amount is not a whole rupee. */
    fun money(paise: Long): String {
        val abs = Math.abs(paise)
        val whole = indian((abs / 100).toString())
        val fraction = if (abs % 100 == 0L) "" else "." + (abs % 100).toString().padStart(2, '0')
        return (if (paise < 0) "-" else "") + "₹" + whole + fraction
    }
    fun day(date: LocalDate): String = date.format(DateTimeFormatter.ofPattern("EEE d MMM", en))
    fun dayMonth(date: LocalDate): String = date.format(DateTimeFormatter.ofPattern("d MMM", en))
    fun range(cycle: Cycle, payday: Int): String =
        if (payday == 1) cycle.start.format(DateTimeFormatter.ofPattern("MMMM yyyy", en)) else "${dayMonth(cycle.start)} – ${dayMonth(cycle.end)}"
    fun ordinal(n: Int): String = n.toString() + when { n in 11..13 -> "th"; n % 10 == 1 -> "st"; n % 10 == 2 -> "nd"; n % 10 == 3 -> "rd"; else -> "th" }
    /** "250.5" or "250.50" typed by the user into paise; null when it is not a positive amount with at most two decimals. */
    fun parsePaise(text: String, maxRupees: Long = 1_000_000_000L): Long? = try {
        val paise = BigDecimal(text.trim().replace(",", "")).movePointRight(2).longValueExact()
        if (paise in 1..maxRupees * 100) paise else null
    } catch (_: Exception) { null }
}

class CategoryMeta(val name: String, val icon: Int, val colorRes: Int) {
    /** Shorter text for narrow chips. */
    val chipLabel: String get() = if (name == "Entertainment") "Fun" else name
}

object Categories {
    val all = listOf(
        CategoryMeta("Food", R.drawable.ic_food, R.color.cat_food),
        CategoryMeta("Transport", R.drawable.ic_move, R.color.cat_move),
        CategoryMeta("Shopping", R.drawable.ic_shop, R.color.cat_shop),
        CategoryMeta("Bills", R.drawable.ic_bills, R.color.cat_bills),
        CategoryMeta("Health", R.drawable.ic_health, R.color.cat_health),
        CategoryMeta("Entertainment", R.drawable.ic_fun, R.color.cat_fun),
        CategoryMeta("Other", R.drawable.ic_other, R.color.cat_other),
    )
    fun of(name: String): CategoryMeta = all.firstOrNull { it.name == name } ?: all.last()
}

object Draw {
    fun dp(context: Context, value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
    fun blend(fg: Int, bg: Int, ratio: Float): Int = Color.rgb(
        (Color.red(fg) * ratio + Color.red(bg) * (1 - ratio)).toInt(),
        (Color.green(fg) * ratio + Color.green(bg) * (1 - ratio)).toInt(),
        (Color.blue(fg) * ratio + Color.blue(bg) * (1 - ratio)).toInt())
    private fun rect(color: Int, radiusPx: Float) = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; setColor(color); cornerRadius = radiusPx }
    /** The "3D" surface used across the app: an edge colour with a face inset from the top, sides and bottom. */
    fun threeD(context: Context, face: Int, edge: Int, radiusDp: Int, topDp: Int = 2, sideDp: Int = 2, bottomDp: Int = 5): Drawable {
        val layers = LayerDrawable(arrayOf(rect(edge, dp(context, radiusDp).toFloat()), rect(face, dp(context, (radiusDp - 2).coerceAtLeast(2)).toFloat())))
        layers.setLayerInset(1, dp(context, sideDp), dp(context, topDp), dp(context, sideDp), dp(context, bottomDp))
        return layers
    }
    fun tint(color: Int): ColorStateList = ColorStateList.valueOf(color)
}

/** Fills one expense row from [item_expense]; the caller adds it to a parent and sets the click action. */
object ExpenseRow {
    fun bind(context: Context, parent: ViewGroup, e: LocalExpense, onClick: () -> Unit): android.view.View {
        val view = LayoutInflater.from(context).inflate(R.layout.item_expense, parent, false)
        val meta = Categories.of(e.category)
        val color = context.getColor(meta.colorRes)
        view.findViewById<FrameLayout>(R.id.exp_icon_bg).background.mutate().setTint(Draw.blend(color, context.getColor(R.color.surface), 0.2f))
        view.findViewById<ImageView>(R.id.exp_icon).apply { setImageResource(meta.icon); imageTintList = Draw.tint(color) }
        view.findViewById<TextView>(R.id.exp_title).text = e.note.ifBlank { e.category }
        view.findViewById<TextView>(R.id.exp_sub).text = Fmt.dayMonth(LocalDate.parse(e.occurredOn)).let { if (e.note.isBlank()) it else "${e.category} · $it" }
        view.findViewById<TextView>(R.id.exp_amount).text = Fmt.money(e.amountMinor)
        view.findViewById<TextView>(R.id.exp_status).apply {
            when (e.status) {
                "pending" -> { text = "Pending sync"; setBackgroundResource(R.drawable.pill_pending); setTextColor(context.getColor(R.color.warn)) }
                "failed" -> { text = "Sync failed · Retry"; setBackgroundResource(R.drawable.pill_failed); setTextColor(context.getColor(R.color.bad)) }
                else -> { text = "Synced"; background = null; setTextColor(context.getColor(R.color.muted)) }
            }
            if (e.status == "pending" || e.status == "failed") setPadding(Draw.dp(context, 8), Draw.dp(context, 2), Draw.dp(context, 8), Draw.dp(context, 2))
        }
        view.setOnClickListener { onClick() }
        return view
    }
}
