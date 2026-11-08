package com.classboard.app.ink

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot

/**
 * 1€ 필터 (One Euro Filter).
 * 천천히 쓸 때는 IR 터치 특유의 떨림을 강하게 눌러주고, 빠르게 쓸 때는 지연이 거의 없게 한다.
 */
class OneEuro(private val minCutoff: Double, private val beta: Double, private val dCutoff: Double = 1.0) {
    private var x = 0.0
    private var dx = 0.0
    private var lastT = -1.0

    fun reset() { lastT = -1.0 }

    private fun alpha(cutoff: Double, dt: Double): Double {
        val tau = 1.0 / (2 * PI * cutoff)
        return 1.0 / (1.0 + tau / dt)
    }

    /** t: 초 단위 시간 */
    fun filter(value: Double, t: Double): Double {
        if (lastT < 0) { lastT = t; x = value; dx = 0.0; return value }
        val dt = (t - lastT).coerceAtLeast(1e-4)
        lastT = t
        val rawDx = (value - x) / dt
        val aD = alpha(dCutoff, dt)
        dx += aD * (rawDx - dx)
        val cutoff = minCutoff + beta * abs(dx)
        x += alpha(cutoff, dt) * (value - x)
        return x
    }
}

/** 2차원 입력 필터 + 다음 위치 예측 (화면 픽셀 기준) */
class InkFilter {
    /** 0 끔, 1 약하게, 2 강하게 */
    var strength = 1
        set(v) { field = v; rebuild() }

    private var fx = OneEuro(4.0, 0.02)
    private var fy = OneEuro(4.0, 0.02)

    var x = 0f; private set
    var y = 0f; private set
    private var px = 0f; private var py = 0f; private var pt = 0.0
    private var vx = 0f; private var vy = 0f

    private fun rebuild() {
        val (mc, b) = when (strength) { 2 -> 1.2 to 0.008; 1 -> 3.5 to 0.015; else -> 0.0 to 0.0 }
        fx = OneEuro(mc, b); fy = OneEuro(mc, b)
    }

    fun start(x0: Float, y0: Float, tMs: Long) {
        fx.reset(); fy.reset()
        val t = tMs / 1000.0
        x = x0; y = y0; px = x0; py = y0; pt = t; vx = 0f; vy = 0f
        if (strength > 0) { fx.filter(x0.toDouble(), t); fy.filter(y0.toDouble(), t) }
    }

    fun add(rx: Float, ry: Float, tMs: Long) {
        val t = tMs / 1000.0
        if (strength > 0) {
            x = fx.filter(rx.toDouble(), t).toFloat(); y = fy.filter(ry.toDouble(), t).toFloat()
        } else { x = rx; y = ry }
        val dt = (t - pt).toFloat()
        if (dt > 0.001f) {
            // 속도 (px/s) 를 부드럽게 갱신
            val nvx = (x - px) / dt; val nvy = (y - py) / dt
            vx = vx * 0.5f + nvx * 0.5f; vy = vy * 0.5f + nvy * 0.5f
            px = x; py = y; pt = t
        }
    }

    /** 약 aheadMs 뒤의 펜 위치 예측. 최대 maxPx 까지만. */
    fun predict(aheadMs: Float, maxPx: Float): Pair<Float, Float> {
        var dx = vx * aheadMs / 1000f; var dy = vy * aheadMs / 1000f
        val d = hypot(dx, dy)
        if (d > maxPx) { dx *= maxPx / d; dy *= maxPx / d }
        return (x + dx) to (y + dy)
    }
}
