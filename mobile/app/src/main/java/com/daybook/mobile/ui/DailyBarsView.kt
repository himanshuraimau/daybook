package com.daybook.mobile.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.daybook.mobile.R

/** One bar per day of a cycle, with an optional dashed per-day budget line. Drawn with Canvas; no chart library. */
class DailyBarsView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private var amounts: List<Long> = emptyList()
    private var firstLabel = ""
    private var midLabel = ""
    private var lastLabel = ""
    private var todayIndex = -1
    private var budgetPerDay: Long? = null
    private var selected = -1
    var onSelect: ((Int) -> Unit)? = null

    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = Draw.dp(context, 2).toFloat(); pathEffect = DashPathEffect(floatArrayOf(Draw.dp(context, 6).toFloat(), Draw.dp(context, 4).toFloat()), 0f) }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = Draw.dp(context, 11).toFloat() * (resources.configuration.fontScale.coerceAtMost(1.3f)); typeface = android.graphics.Typeface.DEFAULT_BOLD }
    private val rect = RectF()

    fun setData(amounts: List<Long>, todayIndex: Int, budgetPerDay: Long?, first: String, mid: String, last: String) {
        this.amounts = amounts; this.todayIndex = todayIndex; this.budgetPerDay = budgetPerDay
        firstLabel = first; midLabel = mid; lastLabel = last; selected = -1
        invalidate()
    }
    fun select(index: Int) { selected = index; invalidate() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val n = amounts.size
        if (n == 0) return
        val labelH = text.textSize * 1.6f
        val top = Draw.dp(context, 6).toFloat()
        val bottom = height - labelH
        val chartH = bottom - top
        val maxValue = maxOf(amounts.maxOrNull() ?: 0L, ((budgetPerDay ?: 0L) * 115 / 100), 1L).toFloat()
        val gap = Draw.dp(context, 3).toFloat()
        val barW = ((width - gap * (n - 1)) / n).coerceAtLeast(2f)
        val radius = (barW / 2f).coerceAtMost(Draw.dp(context, 4).toFloat())
        val track = context.getColor(R.color.surface2)
        for (i in 0 until n) {
            val x = i * (barW + gap)
            val h = (amounts[i] / maxValue * chartH).coerceAtLeast(if (amounts[i] > 0) Draw.dp(context, 3).toFloat() else 0f)
            bar.color = track
            rect.set(x, bottom - Draw.dp(context, 3), x + barW, bottom); canvas.drawRoundRect(rect, radius, radius, bar)
            if (amounts[i] > 0) {
                bar.color = when {
                    i == selected -> context.getColor(R.color.ink)
                    i == todayIndex -> context.getColor(R.color.amber)
                    budgetPerDay != null && amounts[i] > budgetPerDay!! -> context.getColor(R.color.bad)
                    else -> context.getColor(R.color.accent)
                }
                rect.set(x, bottom - h, x + barW, bottom); canvas.drawRoundRect(rect, radius, radius, bar)
            }
        }
        budgetPerDay?.let {
            val y = bottom - it / maxValue * chartH
            line.color = context.getColor(R.color.ink); canvas.drawLine(0f, y, width.toFloat(), y, line)
        }
        text.color = context.getColor(R.color.muted)
        val baseline = height - text.descent() - Draw.dp(context, 1)
        text.textAlign = Paint.Align.LEFT; canvas.drawText(firstLabel, 0f, baseline, text)
        text.textAlign = Paint.Align.CENTER; canvas.drawText(midLabel, width / 2f, baseline, text)
        text.textAlign = Paint.Align.RIGHT; canvas.drawText(lastLabel, width.toFloat(), baseline, text)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val n = amounts.size
        if (n == 0) return false
        if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_MOVE) {
            val index = (event.x / width * n).toInt().coerceIn(0, n - 1)
            if (index != selected) { selected = index; invalidate(); onSelect?.invoke(index) }
            parent?.requestDisallowInterceptTouchEvent(true)
            return true
        }
        if (event.action == MotionEvent.ACTION_UP) { performClick(); return true }
        return super.onTouchEvent(event)
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}
