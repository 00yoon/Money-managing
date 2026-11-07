package com.classboard.app.tools

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.classboard.app.ink.Geometry
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 수학 보조도구 (자, 각도기, 컴퍼스). 모든 좌표는 페이지 좌표.
 * s = 화면 픽셀 / 페이지 단위 (손잡이 크기를 화면 기준으로 일정하게 유지하는 데 사용)
 */
interface ToolDrag {
    /** true 면 이 드래그가 잉크(획)를 만든다 */
    val draws: Boolean
    fun move(x: Float, y: Float)
    /** draws == true 일 때 현재까지의 획 좌표 */
    fun points(): FloatArray? = null
    /** 화면에 띄울 측정값 (각도, 길이 등) */
    fun label(): String? = null
}

abstract class MathTool {
    var visible = false
    /** 자 눈금 단위 (페이지 짧은 변의 1/20) */
    var unit = 30f

    abstract fun placeAt(cx: Float, cy: Float, unit: Float)
    abstract fun draw(c: Canvas, s: Float)
    abstract fun hit(x: Float, y: Float, s: Float): ToolDrag?

    protected val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    protected val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.rgb(40, 40, 40) }
    protected val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(30, 30, 30); textAlign = Paint.Align.CENTER }
    protected val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 30, 91, 184) }

    protected fun fmtUnits(len: Float) = "%.1f".format(len / unit)

    protected inner class MoveDrag(private val get: () -> Pair<Float, Float>, private val set: (Float, Float) -> Unit, x: Float, y: Float) : ToolDrag {
        private val ox: Float; private val oy: Float
        init { val (cx, cy) = get(); ox = cx - x; oy = cy - y }
        override val draws = false
        override fun move(x: Float, y: Float) = set(x + ox, y + oy)
    }

    companion object {
        /** 각도를 15° 배수 근처면 맞추기 (라디안) */
        fun snapRotation(a: Double, tolDeg: Double = 1.5): Double {
            val deg = Math.toDegrees(a)
            val s = Math.round(deg / 15.0) * 15.0
            return if (abs(deg - s) < tolDeg) Math.toRadians(s) else a
        }

        fun normDeg(a: Double): Int {
            var d = Math.toDegrees(a).roundToInt() % 360
            if (d < 0) d += 360
            return d
        }
    }
}

// ------------------------------------------------------------------ 자
class Ruler : MathTool() {
    var cx = 0f; var cy = 0f
    var angle = 0.0
    var length = 450f
    var width = 66f

    override fun placeAt(cx: Float, cy: Float, unit: Float) {
        this.cx = cx; this.cy = cy; this.unit = unit
        length = unit * 16f; width = unit * 2.2f; angle = 0.0
    }

    private fun toLocal(x: Float, y: Float): Pair<Float, Float> {
        val dx = x - cx; val dy = y - cy; val c = cos(angle).toFloat(); val s = sin(angle).toFloat()
        return (dx * c + dy * s) to (-dx * s + dy * c)
    }

    private fun toWorld(u: Float, v: Float): Pair<Float, Float> {
        val c = cos(angle).toFloat(); val s = sin(angle).toFloat()
        return (cx + u * c - v * s) to (cy + u * s + v * c)
    }

    private val knobU get() = length / 2f - width * 0.5f

    override fun draw(c: Canvas, s: Float) {
        c.save()
        c.translate(cx, cy)
        c.rotate(Math.toDegrees(angle).toFloat())
        val hl = length / 2f; val hw = width / 2f
        fill.color = Color.argb(150, 255, 236, 153)
        c.drawRect(-hl, -hw, hl, hw, fill)
        line.strokeWidth = 1.5f / s
        c.drawRect(-hl, -hw, hl, hw, line)
        // 눈금 (위쪽 변)
        val start = -hl + unit * 0.5f
        val minor = if (unit / 10f * s >= 4f) unit / 10f else unit / 2f
        val count = ((length - unit) / minor).toInt()
        text.textSize = unit * 0.42f
        for (k in 0..count) {
            val u = start + k * minor
            val isMajor = abs((k * minor) / unit - Math.round((k * minor) / unit)) < 1e-3
            val half = (minor < unit / 2f) && !isMajor && (k % 5 == 0)
            val len = when { isMajor -> width * 0.32f; half -> width * 0.22f; else -> width * 0.13f }
            c.drawLine(u, -hw, u, -hw + len, line)
            if (isMajor) c.drawText(Math.round((k * minor) / unit).toString(), u, -hw + len + text.textSize, text)
        }
        // 회전 손잡이
        val kr = width * 0.28f
        c.drawCircle(knobU, hw * 0.35f, kr, knob)
        text.color = Color.WHITE; text.textSize = kr * 1.2f
        c.drawText("↻", knobU, hw * 0.35f + kr * 0.42f, text)
        text.color = Color.rgb(30, 30, 30)
        // 현재 각도
        text.textSize = unit * 0.5f
        val deg = (normDeg(angle) % 180)
        c.drawText("${deg}°", 0f, hw * 0.7f, text)
        c.restore()
    }

    override fun hit(x: Float, y: Float, s: Float): ToolDrag? {
        val (u, v) = toLocal(x, y)
        val band = 30f / s
        val hl = length / 2f; val hw = width / 2f
        if (hypot(u - knobU, v - hw * 0.35f) < width * 0.4f) return RotateDrag(x, y)
        if (abs(u) <= hl) {
            val inner = width * 0.22f
            if (v < -hw + inner && v > -hw - band) return EdgeDrag(-hw, u)
            if (v > hw - inner && v < hw + band) return EdgeDrag(hw, u)
            if (abs(v) <= hw) return MoveDrag({ cx to cy }, { a, b -> cx = a; cy = b }, x, y)
        }
        return null
    }

    private inner class RotateDrag(x: Float, y: Float) : ToolDrag {
        private val off = atan2((y - cy).toDouble(), (x - cx).toDouble()) - angle
        override val draws = false
        override fun move(x: Float, y: Float) { angle = snapRotation(atan2((y - cy).toDouble(), (x - cx).toDouble()) - off) }
        override fun label() = "${normDeg(angle) % 180}°"
    }

    /** 자의 변을 따라 직선 긋기 */
    private inner class EdgeDrag(private val edge: Float, startU: Float) : ToolDrag {
        private val u0 = startU.coerceIn(-length / 2f, length / 2f)
        private var u1 = u0
        override val draws = true
        override fun move(x: Float, y: Float) { u1 = toLocal(x, y).first.coerceIn(-length / 2f, length / 2f) }
        override fun points(): FloatArray {
            val (ax, ay) = toWorld(u0, edge); val (bx, by) = toWorld(u1, edge)
            return floatArrayOf(ax, ay, bx, by)
        }
        override fun label() = fmtUnits(abs(u1 - u0))
    }
}

// ------------------------------------------------------------------ 각도기
class Protractor : MathTool() {
    var cx = 0f; var cy = 0f
    var angle = 0.0
    var radius = 210f

    override fun placeAt(cx: Float, cy: Float, unit: Float) {
        this.unit = unit; radius = unit * 7f
        this.cx = cx; this.cy = cy + radius * 0.4f; angle = 0.0
    }

    private fun toLocal(x: Float, y: Float): Pair<Float, Float> {
        val dx = x - cx; val dy = y - cy; val c = cos(angle).toFloat(); val s = sin(angle).toFloat()
        return (dx * c + dy * s) to (-dx * s + dy * c)
    }

    private fun toWorld(u: Float, v: Float): Pair<Float, Float> {
        val c = cos(angle).toFloat(); val s = sin(angle).toFloat()
        return (cx + u * c - v * s) to (cy + u * s + v * c)
    }

    private val strip get() = unit * 0.6f
    private val knobV get() = -radius * 0.42f
    private val shape = Path()

    override fun draw(c: Canvas, s: Float) {
        c.save()
        c.translate(cx, cy)
        c.rotate(Math.toDegrees(angle).toFloat())
        val r = radius
        shape.rewind()
        shape.addArc(RectF(-r, -r, r, r), 180f, 180f)
        shape.lineTo(r, strip); shape.lineTo(-r, strip); shape.close()
        fill.color = Color.argb(140, 165, 216, 255)
        c.drawPath(shape, fill)
        line.strokeWidth = 1.5f / s
        c.drawPath(shape, line)
        c.drawLine(-r, 0f, r, 0f, line)
        // 눈금
        text.textSize = r * 0.065f
        val small = Paint(text).apply { textSize = r * 0.05f; color = Color.rgb(90, 90, 90) }
        for (d in 0..180) {
            val t = Math.toRadians(d.toDouble())
            val len = when { d % 10 == 0 -> 0.12f; d % 5 == 0 -> 0.08f; else -> 0.04f } * r
            val ox = (r * cos(t)).toFloat(); val oy = (-r * sin(t)).toFloat()
            val ix = ((r - len) * cos(t)).toFloat(); val iy = (-(r - len) * sin(t)).toFloat()
            c.drawLine(ox, oy, ix, iy, line)
            if (d % 10 == 0) {
                val lr = r * 0.80f
                c.drawText(d.toString(), (lr * cos(t)).toFloat(), (-lr * sin(t)).toFloat() + text.textSize * 0.35f, text)
                val lr2 = r * 0.69f
                c.drawText((180 - d).toString(), (lr2 * cos(t)).toFloat(), (-lr2 * sin(t)).toFloat() + small.textSize * 0.35f, small)
            }
        }
        // 중심점
        val cr = 10f / s
        c.drawCircle(0f, 0f, cr, line)
        c.drawLine(-cr * 1.6f, 0f, cr * 1.6f, 0f, line)
        c.drawLine(0f, -cr * 1.6f, 0f, cr * 0.6f, line)
        // 회전 손잡이
        val kr = r * 0.09f
        c.drawCircle(0f, knobV, kr, knob)
        text.color = Color.WHITE; text.textSize = kr * 1.2f
        c.drawText("↻", 0f, knobV + kr * 0.42f, text)
        text.color = Color.rgb(30, 30, 30)
        c.restore()
    }

    override fun hit(x: Float, y: Float, s: Float): ToolDrag? {
        val (u, v) = toLocal(x, y)
        val d = hypot(u, v)
        if (d < 34f / s) return RayDrag()
        if (hypot(u, v - knobV) < radius * 0.12f) return RotateDrag(x, y)
        if ((d <= radius && v <= 0f) || (abs(u) <= radius && v in 0f..strip)) {
            return MoveDrag({ cx to cy }, { a, b -> cx = a; cy = b }, x, y)
        }
        return null
    }

    private inner class RotateDrag(x: Float, y: Float) : ToolDrag {
        private val off = atan2((y - cy).toDouble(), (x - cx).toDouble()) - angle
        override val draws = false
        override fun move(x: Float, y: Float) { angle = snapRotation(atan2((y - cy).toDouble(), (x - cx).toDouble()) - off) }
        override fun label() = "${normDeg(angle)}°"
    }

    /** 중심에서 1° 단위로 반직선 긋기 */
    private inner class RayDrag : ToolDrag {
        private var deg = 0
        private var len = 0f
        override val draws = true
        override fun move(x: Float, y: Float) {
            val (u, v) = toLocal(x, y)
            len = hypot(u, v)
            var a = Math.toDegrees(atan2(-v.toDouble(), u.toDouble())).roundToInt()
            if (a < 0) a += 360
            deg = a
        }
        override fun points(): FloatArray? {
            if (len < 1f) return null
            val t = Math.toRadians(deg.toDouble())
            val (ex, ey) = toWorld((len * cos(t)).toFloat(), (-len * sin(t)).toFloat())
            return floatArrayOf(cx, cy, ex, ey)
        }
        override fun label() = if (deg <= 180) "${deg}°  (${180 - deg}°)" else "${deg}°"
    }
}

// ------------------------------------------------------------------ 컴퍼스
class Compass : MathTool() {
    var cx = 0f; var cy = 0f
    var r = 150f
    var theta = 0.0

    override fun placeAt(cx: Float, cy: Float, unit: Float) {
        this.unit = unit; this.cx = cx; this.cy = cy; r = unit * 4f; theta = -PI / 6
    }

    private val px get() = cx + r * cos(theta).toFloat()
    private val py get() = cy + r * sin(theta).toFloat()
    private var preview: FloatArray? = null

    override fun draw(c: Canvas, s: Float) {
        val kr = 22f / s
        // 반지름 원 (흐리게)
        line.strokeWidth = 1f / s
        line.color = Color.argb(70, 30, 91, 184)
        c.drawCircle(cx, cy, r, line)
        line.color = Color.rgb(40, 40, 40)
        // 다리
        line.strokeWidth = 4f / s
        val mx = (cx + px) / 2f; val my = (cy + py) / 2f
        // 다리 굽힘 (손잡이 느낌)
        val nx = -(py - cy) / r; val ny = (px - cx) / r
        val hx = mx + nx * r * 0.18f; val hy = my + ny * r * 0.18f
        c.drawLine(cx, cy, hx, hy, line)
        c.drawLine(hx, hy, px, py, line)
        line.strokeWidth = 1.5f / s
        // 중심(바늘)
        fill.color = Color.argb(220, 120, 120, 120)
        c.drawCircle(cx, cy, kr, fill)
        c.drawLine(cx - kr, cy, cx + kr, cy, line); c.drawLine(cx, cy - kr, cx, cy + kr, line)
        // 반지름 조절 손잡이
        fill.color = Color.argb(220, 255, 255, 255)
        c.drawCircle(hx, hy, kr * 0.8f, fill); c.drawCircle(hx, hy, kr * 0.8f, line)
        // 연필
        c.drawCircle(px, py, kr, knob)
        text.color = Color.WHITE; text.textSize = kr * 1.1f
        c.drawText("✎", px, py + kr * 0.4f, text)
        text.color = Color.rgb(30, 30, 30)
        // 반지름 표시
        text.textSize = 20f / s
        c.drawText("r = ${fmtUnits(r)}", hx + nx * 34f / s, hy + ny * 34f / s, text)
    }

    override fun hit(x: Float, y: Float, s: Float): ToolDrag? {
        val kr = 34f / s
        if (hypot(x - px, y - py) < kr) return ArcDrag(x, y)
        val mx = (cx + px) / 2f; val my = (cy + py) / 2f
        val nx = -(py - cy) / r; val ny = (px - cx) / r
        val hx = mx + nx * r * 0.18f; val hy = my + ny * r * 0.18f
        if (hypot(x - hx, y - hy) < kr) return AdjustDrag()
        if (hypot(x - cx, y - cy) < kr) return MoveDrag({ cx to cy }, { a, b -> cx = a; cy = b }, x, y)
        return null
    }

    /** 반지름/방향 조절 (그리지 않음) */
    private inner class AdjustDrag : ToolDrag {
        override val draws = false
        override fun move(x: Float, y: Float) {
            // 손잡이는 다리 중간에서 바깥으로 0.18r 떨어져 있음 → 중심에서 약 0.5315r 거리
            val d = hypot(x - cx, y - cy) / 0.5315f
            r = d.coerceAtLeast(unit * 0.5f)
            theta = atan2((y - cy).toDouble(), (x - cx).toDouble()) - Math.atan2(0.18 * 2, 1.0)
        }
        override fun label() = "r = ${fmtUnits(r)}"
    }

    /** 연필을 돌려 호 그리기 */
    private inner class ArcDrag(x: Float, y: Float) : ToolDrag {
        private val start = theta
        private var last = atan2((y - cy).toDouble(), (x - cx).toDouble())
        private var sweep = 0.0
        override val draws = true
        override fun move(x: Float, y: Float) {
            val a = atan2((y - cy).toDouble(), (x - cx).toDouble())
            var d = a - last
            while (d > PI) d -= 2 * PI
            while (d < -PI) d += 2 * PI
            last = a
            sweep = (sweep + d).coerceIn(-2 * PI, 2 * PI)
            theta = start + sweep
        }
        override fun points(): FloatArray? {
            if (abs(sweep) < 0.01) return null
            return Geometry.ellipse(cx, cy, r, r, 0f, start, sweep)
        }
        override fun label() = "${Math.toDegrees(abs(sweep)).roundToInt()}°"
    }
}
