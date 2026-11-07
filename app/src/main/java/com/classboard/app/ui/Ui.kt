package com.classboard.app.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/** 큰 화면(전자칠판)에서 누르기 쉬운 버튼을 코드로 만드는 도우미 */
object Ui {
    fun dp(c: Context, v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.resources.displayMetrics)
    fun dpi(c: Context, v: Float) = dp(c, v).toInt()

    const val BAR_BG = 0xF2FFFFFF.toInt()
    const val SELECTED = 0xFF1E5BB8.toInt()
    const val TEXT = 0xFF212529.toInt()

    fun rounded(color: Int, radius: Float, stroke: Int = 0, strokeColor: Int = 0) = GradientDrawable().apply {
        setColor(color); cornerRadius = radius
        if (stroke > 0) setStroke(stroke, strokeColor)
    }

    fun button(c: Context, label: String, sizeSp: Float = 17f, onClick: (View) -> Unit): TextView = TextView(c).apply {
        text = label
        setTextColor(TEXT)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        gravity = Gravity.CENTER
        minWidth = dpi(c, 56f); minHeight = dpi(c, 56f)
        val ph = dpi(c, 12f); setPadding(ph, 0, ph, 0)
        background = pressable(c, Color.TRANSPARENT)
        setOnClickListener(onClick)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dpi(c, 56f)).apply {
            val m = dpi(c, 2f); setMargins(m, m, m, m)
        }
    }

    fun pressable(c: Context, base: Int) = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), rounded(0x331E5BB8, dp(c, 10f)))
        addState(intArrayOf(), rounded(base, dp(c, 10f)))
    }

    fun setSelected(v: TextView, sel: Boolean) {
        val c = v.context
        v.background = if (sel) rounded(SELECTED, dp(c, 10f)) else pressable(c, Color.TRANSPARENT)
        v.setTextColor(if (sel) Color.WHITE else TEXT)
    }

    /** 색상 동그라미 */
    fun colorDot(c: Context, color: Int, onClick: (View) -> Unit): View = View(c).apply {
        val s = dpi(c, 44f)
        layoutParams = LinearLayout.LayoutParams(s, s).apply { val m = dpi(c, 6f); setMargins(m, m, m, m) }
        tag = color
        setDot(this, false)
        setOnClickListener(onClick)
    }

    fun setDot(v: View, sel: Boolean) {
        val c = v.context
        val color = v.tag as Int
        v.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            if (sel) setStroke(dpi(c, 4f), SELECTED)
            else setStroke(dpi(c, 1.5f), 0xFFADB5BD.toInt())
        }
        v.scaleX = if (sel) 1.15f else 1f; v.scaleY = v.scaleX
    }

    fun divider(c: Context): View = View(c).apply {
        setBackgroundColor(0xFFDEE2E6.toInt())
        layoutParams = LinearLayout.LayoutParams(dpi(c, 1.5f), dpi(c, 36f)).apply { val m = dpi(c, 6f); setMargins(m, 0, m, 0); gravity = Gravity.CENTER_VERTICAL }
    }
}
