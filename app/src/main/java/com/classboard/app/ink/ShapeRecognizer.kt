package com.classboard.app.ink

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 손으로 그린 획을 직선 / 꺾은선 / 삼각형 / 사각형 / 다각형 / 원 / 타원 으로 보정한다.
 * 확신이 없으면 null 을 돌려주어 원래 획을 유지한다.
 */
object ShapeRecognizer {

    class Result(val name: String, val pts: FloatArray)

    fun recognize(raw: FloatArray): Result? {
        val n = raw.size / 2
        if (n < 3) return null
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        var pathLen = 0f
        for (i in 0 until n) {
            val x = raw[i * 2]; val y = raw[i * 2 + 1]
            minX = min(minX, x); maxX = max(maxX, x); minY = min(minY, y); maxY = max(maxY, y)
            if (i > 0) pathLen += hypot(x - raw[i * 2 - 2], y - raw[i * 2 - 1])
        }
        val diag = hypot(maxX - minX, maxY - minY)
        if (diag < 8f || pathLen < 10f) return null

        val sx = raw[0]; val sy = raw[1]; val ex = raw[n * 2 - 2]; val ey = raw[n * 2 - 1]
        val gap = hypot(ex - sx, ey - sy)

        // 1) 직선: 시작-끝 거리 ≈ 경로 길이
        if (gap / pathLen > 0.93f) {
            val (x1, y1) = Geometry.snapAngle(sx, sy, ex, ey, 5.0)
            return Result("직선", floatArrayOf(sx, sy, x1, y1))
        }

        val closed = gap < max(diag * 0.22f, pathLen * 0.08f)
        val eps = diag * 0.045f
        val simple = rdp(raw, eps)

        if (!closed) {
            // 열린 꺾은선 (2~3 개 선분) - 예: 각, 그래프 축
            val segs = simple.size / 2 - 1
            if (segs in 2..3 && fitError(raw, simple, false) < diag * 0.035f) {
                return Result("꺾은선", snapPolyline(simple))
            }
            return null
        }

        // 닫힌 도형: 다각형 vs 타원 중 오차가 작은 것
        val poly = closePolygon(simple, diag)
        val polyErr = if (poly != null) fitError(raw, poly, true) else Float.MAX_VALUE
        val ell = fitEllipse(raw)
        val ellErr = ell?.second ?: Float.MAX_VALUE

        val corners = if (poly != null) poly.size / 2 else 0
        if (poly != null && corners in 3..6 && polyErr < diag * 0.04f && polyErr <= ellErr * 1.15f) {
            val name = when (corners) { 3 -> "삼각형"; 4 -> "사각형"; else -> "${corners}각형" }
            val pts = if (corners == 4) squareUp(poly) else poly
            return Result(name, closeLoop(pts))
        }
        if (ell != null && ellErr < diag * 0.05f) return Result("원", ell.first)
        return null
    }

    // Ramer–Douglas–Peucker 단순화
    private fun rdp(p: FloatArray, eps: Float): FloatArray {
        val n = p.size / 2
        val keep = BooleanArray(n); keep[0] = true; keep[n - 1] = true
        val stack = ArrayDeque<Pair<Int, Int>>(); stack.addLast(0 to n - 1)
        val e2 = eps * eps
        while (stack.isNotEmpty()) {
            val (a, b) = stack.removeLast()
            var best = -1; var bestD = e2
            for (i in a + 1 until b) {
                val d = Geometry.segDist2(p[i * 2], p[i * 2 + 1], p[a * 2], p[a * 2 + 1], p[b * 2], p[b * 2 + 1])
                if (d > bestD) { bestD = d; best = i }
            }
            if (best >= 0) { keep[best] = true; stack.addLast(a to best); stack.addLast(best to b) }
        }
        val out = FloatBuf()
        for (i in 0 until n) if (keep[i]) out.add(p[i * 2], p[i * 2 + 1])
        return out.toArray()
    }

    /** 단순화된 닫힌 꺾은선 → 꼭짓점 목록 (시작/끝 점 병합, 거의 일직선인 꼭짓점 제거) */
    private fun closePolygon(s: FloatArray, diag: Float): FloatArray? {
        val pts = ArrayList<Pair<Float, Float>>()
        var i = 0
        while (i < s.size) { pts += s[i] to s[i + 1]; i += 2 }
        if (pts.size >= 2) {
            val f = pts.first(); val l = pts.last()
            pts.removeAt(pts.size - 1)
            pts[0] = (f.first + l.first) / 2f to (f.second + l.second) / 2f
        }
        // 너무 가까운 점 병합
        var changed = true
        while (changed && pts.size > 3) {
            changed = false
            for (k in pts.indices) {
                val a = pts[k]; val b = pts[(k + 1) % pts.size]
                if (hypot(a.first - b.first, a.second - b.second) < diag * 0.12f) {
                    pts[k] = (a.first + b.first) / 2f to (a.second + b.second) / 2f
                    pts.removeAt((k + 1) % pts.size); changed = true; break
                }
            }
        }
        // 거의 180° 인 꼭짓점 제거
        changed = true
        while (changed && pts.size > 3) {
            changed = false
            for (k in pts.indices) {
                val a = pts[(k - 1 + pts.size) % pts.size]; val b = pts[k]; val c = pts[(k + 1) % pts.size]
                if (angleAt(a, b, c) > 155.0) { pts.removeAt(k); changed = true; break }
            }
        }
        if (pts.size < 3) return null
        return FloatArray(pts.size * 2).also { out -> pts.forEachIndexed { k, p -> out[k * 2] = p.first; out[k * 2 + 1] = p.second } }
    }

    private fun angleAt(a: Pair<Float, Float>, b: Pair<Float, Float>, c: Pair<Float, Float>): Double {
        val v1x = a.first - b.first; val v1y = a.second - b.second
        val v2x = c.first - b.first; val v2y = c.second - b.second
        val d = hypot(v1x, v1y) * hypot(v2x, v2y)
        if (d == 0f) return 180.0
        return Math.toDegrees(acos(((v1x * v2x + v1y * v2y) / d).toDouble().coerceIn(-1.0, 1.0)))
    }

    /** 원래 점들과 근사 도형 사이의 평균 거리 */
    private fun fitError(raw: FloatArray, shape: FloatArray, closed: Boolean): Float {
        val m = shape.size / 2
        var sum = 0.0
        val n = raw.size / 2
        for (i in 0 until n) {
            val x = raw[i * 2]; val y = raw[i * 2 + 1]
            var best = Float.MAX_VALUE
            val segs = if (closed) m else m - 1
            for (k in 0 until segs) {
                val a = k; val b = (k + 1) % m
                best = min(best, Geometry.segDist2(x, y, shape[a * 2], shape[a * 2 + 1], shape[b * 2], shape[b * 2 + 1]))
            }
            sum += sqrt(best.toDouble())
        }
        return (sum / n).toFloat()
    }

    /** 주성분 분석으로 타원 근사. (점들, 평균오차) */
    private fun fitEllipse(raw: FloatArray): Pair<FloatArray, Float>? {
        val n = raw.size / 2
        var cx = 0.0; var cy = 0.0
        for (i in 0 until n) { cx += raw[i * 2]; cy += raw[i * 2 + 1] }
        cx /= n; cy /= n
        var sxx = 0.0; var syy = 0.0; var sxy = 0.0
        for (i in 0 until n) {
            val dx = raw[i * 2] - cx; val dy = raw[i * 2 + 1] - cy
            sxx += dx * dx; syy += dy * dy; sxy += dx * dy
        }
        var rot = 0.5 * atan2(2 * sxy, sxx - syy)
        // 축 방향 반지름: 투영 범위의 절반
        fun extents(r: Double): Pair<Double, Double> {
            var minU = Double.MAX_VALUE; var maxU = -Double.MAX_VALUE; var minV = Double.MAX_VALUE; var maxV = -Double.MAX_VALUE
            val c = cos(r); val s = sin(r)
            for (i in 0 until n) {
                val dx = raw[i * 2] - cx; val dy = raw[i * 2 + 1] - cy
                val u = dx * c + dy * s; val v = -dx * s + dy * c
                minU = min(minU, u); maxU = max(maxU, u); minV = min(minV, v); maxV = max(maxV, v)
            }
            return (maxU - minU) / 2 to (maxV - minV) / 2
        }
        var (rx, ry) = extents(rot)
        if (rx < 2 || ry < 2) return null
        // 거의 원이면 정원으로, 축에 가까우면 수평/수직으로
        if (min(rx, ry) / max(rx, ry) > 0.85) { val r = (rx + ry) / 2; rx = r; ry = r; rot = 0.0 }
        else {
            val deg = Math.toDegrees(rot)
            val snapped = Math.round(deg / 90.0) * 90.0
            if (abs(deg - snapped) < 8) { rot = Math.toRadians(snapped); val e = extents(rot); rx = e.first; ry = e.second }
        }
        // 오차: 각 점의 타원 위 정규화 거리
        var err = 0.0
        val c = cos(rot); val s = sin(rot)
        for (i in 0 until n) {
            val dx = raw[i * 2] - cx; val dy = raw[i * 2 + 1] - cy
            val u = dx * c + dy * s; val v = -dx * s + dy * c
            val k = sqrt((u / rx) * (u / rx) + (v / ry) * (v / ry))
            err += abs(k - 1) * (rx + ry) / 2
        }
        err /= n
        val pts = Geometry.ellipse(cx.toFloat(), cy.toFloat(), rx.toFloat(), ry.toFloat(), rot.toFloat())
        return pts to err.toFloat()
    }

    /** 사각형 각도를 정리: 각 변의 방향을 수평/수직 근처면 맞추고, 직사각형에 가까우면 직사각형으로 */
    private fun squareUp(p: FloatArray): FloatArray {
        // 모든 각이 90° 근처면 회전된 직사각형으로 맞춤
        val pts = List(4) { p[it * 2] to p[it * 2 + 1] }
        val nearRight = (0 until 4).all { abs(angleAt(pts[(it + 3) % 4], pts[it], pts[(it + 1) % 4]) - 90.0) < 14.0 }
        if (!nearRight) return p
        val ang = atan2((pts[1].second - pts[0].second).toDouble(), (pts[1].first - pts[0].first).toDouble())
        var rot = ang
        val deg = Math.toDegrees(ang)
        val snapped = Math.round(deg / 90.0) * 90.0
        if (abs(deg - snapped) < 8) rot = Math.toRadians(snapped)
        val c = cos(rot); val s = sin(rot)
        var cx = 0.0; var cy = 0.0
        pts.forEach { cx += it.first; cy += it.second }; cx /= 4; cy /= 4
        var minU = Double.MAX_VALUE; var maxU = -Double.MAX_VALUE; var minV = Double.MAX_VALUE; var maxV = -Double.MAX_VALUE
        pts.forEach {
            val dx = it.first - cx; val dy = it.second - cy
            val u = dx * c + dy * s; val v = -dx * s + dy * c
            minU = min(minU, u); maxU = max(maxU, u); minV = min(minV, v); maxV = max(maxV, v)
        }
        val hu = (maxU - minU) / 2; val hv = (maxV - minV) / 2
        val ou = (maxU + minU) / 2; val ov = (maxV + minV) / 2
        val corners = listOf(-hu to -hv, hu to -hv, hu to hv, -hu to hv)
        val out = FloatArray(8)
        corners.forEachIndexed { k, (u0, v0) ->
            val u = u0 + ou; val v = v0 + ov
            out[k * 2] = (cx + u * c - v * s).toFloat(); out[k * 2 + 1] = (cy + u * s + v * c).toFloat()
        }
        return out
    }

    private fun snapPolyline(p: FloatArray): FloatArray {
        val out = p.copyOf()
        var i = 2
        while (i < out.size) {
            val (x, y) = Geometry.snapAngle(out[i - 2], out[i - 1], out[i], out[i + 1], 5.0)
            out[i] = x; out[i + 1] = y
            i += 2
        }
        return out
    }

    private fun closeLoop(p: FloatArray): FloatArray = p.copyOf(p.size + 2).also { it[p.size] = p[0]; it[p.size + 1] = p[1] }
}
