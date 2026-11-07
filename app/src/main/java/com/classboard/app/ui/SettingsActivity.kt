package com.classboard.app.ui

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import com.classboard.app.Prefs
import kotlin.math.max

/**
 * 설정 + 터치 보정.
 * IR 전자칠판은 펜 끝 / 펜 뒤(굵은 쪽) / 손바닥의 접촉 크기가 다르게 들어오므로,
 * 실제로 대 보고 그 값을 기준으로 손바닥 지우개 기준값을 정한다.
 */
class SettingsActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var palmLabel: TextView
    private lateinit var thickLabel: TextView
    private lateinit var palmSeek: SeekBar
    private lateinit var thickSeek: SeekBar
    private lateinit var measureLabel: TextView

    private var penMax = 0f
    private var thickMax = 0f
    private var palmMin = 0f
    private var measuring = 0 // 0 없음, 1 펜 끝, 2 굵은 쪽, 3 손바닥

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        // 왼쪽: 설정 항목
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; val p = Ui.dpi(context, 24f); setPadding(p, p, p, p) }
        val scroll = ScrollView(this).apply { addView(col) }
        root.addView(scroll, LinearLayout.LayoutParams(0, -1, 1f))

        col.addView(title("설정"))
        col.addView(switch("손바닥으로 지우기", prefs.palmErase) { prefs.palmErase = it })
        palmLabel = label("")
        col.addView(palmLabel)
        palmSeek = seek(400, prefs.palmThreshold.toInt()) { prefs.palmThreshold = it.toFloat().coerceAtLeast(1f); updateLabels() }
        col.addView(palmSeek)

        col.addView(switch("펜의 굵은 쪽을 지우개로 사용", prefs.thickEraser) { prefs.thickEraser = it })
        thickLabel = label("")
        col.addView(thickLabel)
        thickSeek = seek(400, prefs.thickThreshold.toInt()) { prefs.thickThreshold = it.toFloat().coerceAtLeast(1f); updateLabels() }
        col.addView(thickSeek)

        col.addView(switch("두 손가락으로 확대 / 이동", prefs.twoFingerZoom) { prefs.twoFingerZoom = it })
        col.addView(switch("그리고 잠시 멈추면 도형으로 보정", prefs.holdToSnap) { prefs.holdToSnap = it })
        col.addView(switch("모든 획을 자동으로 도형 보정", prefs.autoShape) { prefs.autoShape = it })

        col.addView(title("터치 보정"))
        col.addView(label("① 아래 버튼을 누르고 ② 오른쪽 회색 영역에 해당 부위로 여러 번 그어 보세요.\n③ '자동 설정'을 누르면 기준값이 정해집니다."))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(Ui.button(this, "펜 끝 측정") { measuring = 1; penMax = 0f; updateLabels() })
        row.addView(Ui.button(this, "굵은 쪽 측정") { measuring = 2; thickMax = 0f; updateLabels() })
        row.addView(Ui.button(this, "손바닥 측정") { measuring = 3; palmMin = 0f; updateLabels() })
        col.addView(row)
        col.addView(Ui.button(this, "✔ 자동 설정") { autoSet() })
        measureLabel = label("")
        col.addView(measureLabel)
        col.addView(Ui.button(this, "← 돌아가기") { finish() })

        // 오른쪽: 측정 영역
        root.addView(TouchProbe(this), LinearLayout.LayoutParams(0, -1, 1f))
        setContentView(root)
        updateLabels()
    }

    private fun autoSet() {
        if (penMax <= 0f || palmMin <= 0f) { measureLabel.text = "펜 끝과 손바닥을 모두 측정해 주세요."; return }
        if (palmMin <= penMax) { measureLabel.text = "손바닥과 펜의 차이가 작아 구분하기 어렵습니다. 손바닥을 넓게 대 보세요."; return }
        if (thickMax > penMax && thickMax < palmMin) {
            prefs.thickThreshold = (penMax + (thickMax - penMax) * 0.5f).coerceAtLeast(penMax + 1f)
            prefs.palmThreshold = (thickMax + palmMin) / 2f
        } else {
            prefs.palmThreshold = (penMax + palmMin) / 2f
        }
        palmSeek.progress = prefs.palmThreshold.toInt()
        thickSeek.progress = prefs.thickThreshold.toInt()
        measureLabel.text = "설정 완료!"
        updateLabels()
    }

    private fun updateLabels() {
        palmLabel.text = "손바닥 판정 기준: 접촉 크기 ${prefs.palmThreshold.toInt()} 이상"
        thickLabel.text = "굵은 쪽 판정 기준: 접촉 크기 ${prefs.thickThreshold.toInt()} 이상"
        val m = when (measuring) { 1 -> "▶ 펜 끝으로 그어 보세요"; 2 -> "▶ 펜의 굵은 쪽으로 그어 보세요"; 3 -> "▶ 손바닥으로 문질러 보세요"; else -> "" }
        measureLabel.text = "$m\n펜 끝 최대: ${penMax.toInt()}   굵은 쪽 최대: ${thickMax.toInt()}   손바닥 최소: ${palmMin.toInt()}"
    }

    private fun onProbe(size: Float, ended: Boolean) {
        when (measuring) {
            1 -> penMax = max(penMax, size)
            2 -> thickMax = max(thickMax, size)
            3 -> if (ended) { /* 손바닥은 한 번 댈 때의 최대값 중 가장 작은 값 */ }
        }
        updateLabels()
    }

    private var palmStrokeMax = 0f
    private fun onPalmEnd() {
        if (measuring == 3 && palmStrokeMax > 0f) {
            palmMin = if (palmMin == 0f) palmStrokeMax else minOf(palmMin, palmStrokeMax)
            updateLabels()
        }
        palmStrokeMax = 0f
    }

    private fun title(s: String) = TextView(this).apply {
        text = s; textSize = 26f; setTextColor(Ui.TEXT); paint.isFakeBoldText = true
        setPadding(0, Ui.dpi(context, 16f), 0, Ui.dpi(context, 8f))
    }

    private fun label(s: String) = TextView(this).apply { text = s; textSize = 17f; setTextColor(0xFF495057.toInt()); setPadding(0, Ui.dpi(context, 6f), 0, Ui.dpi(context, 6f)) }

    @Suppress("UseSwitchCompatOrMaterialCode")
    private fun switch(s: String, v: Boolean, on: (Boolean) -> Unit) = Switch(this).apply {
        text = s; textSize = 19f; isChecked = v
        setPadding(0, Ui.dpi(context, 10f), 0, Ui.dpi(context, 10f))
        setOnCheckedChangeListener { _: CompoundButton, c: Boolean -> on(c) }
    }

    private fun seek(maxV: Int, v: Int, on: (Int) -> Unit) = SeekBar(this).apply {
        max = maxV; progress = v
        minimumHeight = Ui.dpi(context, 48f)
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) on(p) }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
    }

    /** 접촉 크기를 실시간으로 보여주는 영역 */
    private inner class TouchProbe(c: Context) : View(c) {
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = Ui.dp(c, 22f); color = Color.rgb(33, 37, 41) }
        private val circle = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4f; color = Color.rgb(30, 91, 184) }
        private val pts = ArrayList<Triple<Float, Float, Float>>()

        init { setBackgroundColor(Color.rgb(233, 236, 239)) }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            pts.clear()
            for (i in 0 until e.pointerCount) {
                val tm = e.getTouchMajor(i)
                val size = if (tm > 0f) tm else e.getSize(i) * 1000f
                pts += Triple(e.getX(i), e.getY(i), size)
                if (measuring == 3) palmStrokeMax = max(palmStrokeMax, size) else onProbe(size, false)
            }
            if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) { onPalmEnd(); pts.clear() }
            invalidate()
            return true
        }

        override fun onDraw(c: Canvas) {
            c.drawText("여기에 대 보세요", Ui.dp(context, 20f), Ui.dp(context, 40f), text)
            var y = Ui.dp(context, 80f)
            for ((x, py, s) in pts) {
                c.drawCircle(x, py, max(8f, s / 2f), circle)
                c.drawText("접촉 크기: ${s.toInt()}", Ui.dp(context, 20f), y, text); y += text.textSize * 1.3f
            }
        }
    }
}
