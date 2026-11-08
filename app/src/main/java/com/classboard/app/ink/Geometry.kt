package com.classboard.app.ink

import android.graphics.Paint
import android.graphics.Path
import android.graphics.DashPathEffect
import com.classboard.app.model.Stroke
import com.classboard.app.model.StrokeKind
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/** 가변 길이 float 버퍼 (획 입력 중 사용) */
class FloatBuf(cap: Int = 256) {
    var data = FloatArray(cap); private set
    var size = 0; private set

    fun add(x: Float, y: Float) {
        if (size + 2 > data.size) data = data.copyOf(data.size * 2)
        data[size++] = x; data[size++] = y
    }

    fun clear() { size = 0 }
    fun toArray(): FloatArray = data.copyOf(size)
    val points get() = size / 2
    fun x(i: Int) = data[i * 2]
    fun y(i: Int) = data[i * 2 + 1]
    fun lastX() = data[size - 2]
    fun lastY() = data[size - 1]
    fun set(arr: FloatArray) { clear(); var i = 0; while (i < arr.size) { add(arr[i], arr[i + 1]); i += 2 } }
}

object Geometry {

    /** 점 사이 간격이 step 이하가 되도록 보간점 추가 (지우개 정확도 향상) */
    fun densify(pts: FloatArray, step: Float): FloatArray {
        if (pts.size < 4) return pts
        val out = FloatBuf(pts.size * 2)
        out.add(pts[0], pts[1])
        var i = 2
        while (i < pts.size) {
            val x0 = pts[i - 2]; val y0 = pts[i - 1]; val x1 = pts[i]; val y1 = pts[i + 1]
            val d = hypot(x1 - x0, y1 - y0)
            if (d > step) {
                val n = (d / step).toInt()
                for (k in 1..n) {
                    val t = k / (n + 1f)
                    out.add(x0 + (x1 - x0) * t, y0 + (y1 - y0) * t)
                }
            }
            out.add(x1, y1)
            i += 2
        }
        return out.toArray()
    }

    /** 원형 지우개로 획을 자른다. 영향이 없으면 null, 있으면 남은 조각 목록 */
    fun erase(s: Stroke, cx: Float, cy: Float, r: Float, allowDensify: Boolean = true): List<Stroke>? {
        val rr = r + s.width / 2f
        val b = s.bounds
        if (cx + rr < b.left || cx - rr > b.right || cy + rr < b.top || cy - rr > b.bottom) return null
        val p = s.pts
        val r2 = rr * rr
        var hit = false
        var i = 0
        while (i < p.size) {
            val dx = p[i] - cx; val dy = p[i + 1] - cy
            if (dx * dx + dy * dy <= r2) { hit = true; break }
            i += 2
        }
        if (!hit) {
            if (!allowDensify) return null
            // 점이 성긴 직선 획을 위해 선분 거리도 확인
            i = 2
            while (i < p.size) {
                if (segDist2(cx, cy, p[i - 2], p[i - 1], p[i], p[i + 1]) <= r2) { hit = true; break }
                i += 2
            }
            if (!hit) return null
            // 선분만 맞았다면 촘촘하게 만든 뒤 다시 계산
            return erase(s.withPoints(densify(p, max(0.5f, r / 4f))), cx, cy, r, false)
        }
        val parts = ArrayList<Stroke>()
        val buf = FloatBuf()
        i = 0
        while (i < p.size) {
            val dx = p[i] - cx; val dy = p[i + 1] - cy
            if (dx * dx + dy * dy <= r2) {
                if (buf.points >= 2) parts += s.withPoints(buf.toArray())
                buf.clear()
            } else buf.add(p[i], p[i + 1])
            i += 2
        }
        if (buf.points >= 2) parts += s.withPoints(buf.toArray())
        return parts
    }

    fun segDist2(px: Float, py: Float, x0: Float, y0: Float, x1: Float, y1: Float): Float {
        val vx = x1 - x0; val vy = y1 - y0
        val len2 = vx * vx + vy * vy
        var t = if (len2 == 0f) 0f else ((px - x0) * vx + (py - y0) * vy) / len2
        t = t.coerceIn(0f, 1f)
        val dx = x0 + vx * t - px; val dy = y0 + vy * t - py
        return dx * dx + dy * dy
    }

    /** 부드러운 곡선 경로 (중간점 2차 베지어) */
    fun buildPath(pts: FloatArray, n: Int = pts.size / 2, path: Path = Path()): Path {
        path.rewind()
        if (n == 0) return path
        path.moveTo(pts[0], pts[1])
        if (n == 1) { path.lineTo(pts[0] + 0.01f, pts[1]); return path }
        if (n == 2) { path.lineTo(pts[2], pts[3]); return path }
        for (i in 1 until n - 1) {
            val x = pts[i * 2]; val y = pts[i * 2 + 1]
            val mx = (x + pts[i * 2 + 2]) / 2f; val my = (y + pts[i * 2 + 3]) / 2f
            path.quadTo(x, y, mx, my)
        }
        path.lineTo(pts[(n - 1) * 2], pts[(n - 1) * 2 + 1])
        return path
    }

    fun configurePaint(paint: Paint, kind: Int, color: Int, width: Float, dashed: Boolean) {
        paint.reset()
        paint.isAntiAlias = true
        paint.style = Paint.Style.STROKE
        paint.strokeJoin = Paint.Join.ROUND
        paint.strokeWidth = width
        if (kind == StrokeKind.HIGHLIGHTER) {
            paint.color = (color and 0x00FFFFFF) or (0x60 shl 24)
            paint.strokeCap = Paint.Cap.SQUARE
        } else {
            paint.color = color
            paint.strokeCap = Paint.Cap.ROUND
        }
        if (dashed) paint.pathEffect = DashPathEffect(floatArrayOf(width * 3f, width * 2.5f), 0f)
    }

    // ----- 도형 생성 (모두 페이지 좌표) -----

    fun line(x0: Float, y0: Float, x1: Float, y1: Float) = floatArrayOf(x0, y0, x1, y1)

    fun polyline(vararg p: Float) = p

    fun ellipse(cx: Float, cy: Float, rx: Float, ry: Float, rot: Float = 0f, a0: Double = 0.0, sweep: Double = Math.PI * 2): FloatArray {
        val steps = max(24, (max(abs(rx), abs(ry)) * abs(sweep) / 6.0).toInt()).coerceAtMost(720)
        val out = FloatArray((steps + 1) * 2)
        val cr = cos(rot.toDouble()); val sr = sin(rot.toDouble())
        for (k in 0..steps) {
            val a = a0 + sweep * k / steps
            val lx = rx * cos(a); val ly = ry * sin(a)
            out[k * 2] = (cx + lx * cr - ly * sr).toFloat()
            out[k * 2 + 1] = (cy + lx * sr + ly * cr).toFloat()
        }
        return out
    }

    fun arrow(x0: Float, y0: Float, x1: Float, y1: Float, head: Float): FloatArray {
        val a = Math.atan2((y1 - y0).toDouble(), (x1 - x0).toDouble())
        val h = head.toDouble()
        val wing = Math.toRadians(28.0)
        val lx = (x1 - h * cos(a - wing)).toFloat(); val ly = (y1 - h * sin(a - wing)).toFloat()
        val rx = (x1 - h * cos(a + wing)).toFloat(); val ry = (y1 - h * sin(a + wing)).toFloat()
        return floatArrayOf(x0, y0, x1, y1, lx, ly, x1, y1, rx, ry)
    }

    fun distance(x0: Float, y0: Float, x1: Float, y1: Float) = hypot(x1 - x0, y1 - y0)

    /** 각도를 15° 배수 근처면 맞춰준다 */
    fun snapAngle(x0: Float, y0: Float, x1: Float, y1: Float, tolDeg: Double = 4.0): Pair<Float, Float> {
        val len = hypot(x1 - x0, y1 - y0).toDouble()
        val a = Math.toDegrees(Math.atan2((y1 - y0).toDouble(), (x1 - x0).toDouble()))
        val snapped = Math.round(a / 15.0) * 15.0
        if (abs(snapped - a) > tolDeg) return x1 to y1
        val r = Math.toRadians(snapped)
        return (x0 + len * cos(r)).toFloat() to (y0 + len * sin(r)).toFloat()
    }
}
