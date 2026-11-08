package com.classboard.app.ink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class ShapeRecognizerTest {
    private val rnd = Random(42)
    private fun jitter(a: Float = 3f) = (rnd.nextFloat() - 0.5f) * 2 * a

    private fun polyStroke(vararg corners: Float, perSeg: Int = 25, noise: Float = 3f): FloatArray {
        val out = FloatBuf()
        val n = corners.size / 2
        for (k in 0 until n - 1) {
            val x0 = corners[k * 2]; val y0 = corners[k * 2 + 1]; val x1 = corners[k * 2 + 2]; val y1 = corners[k * 2 + 3]
            for (i in 0 until perSeg) {
                val t = i / perSeg.toFloat()
                out.add(x0 + (x1 - x0) * t + jitter(noise), y0 + (y1 - y0) * t + jitter(noise))
            }
        }
        out.add(corners[corners.size - 2], corners[corners.size - 1])
        return out.toArray()
    }

    @Test fun line() {
        val r = ShapeRecognizer.recognize(polyStroke(100f, 100f, 500f, 104f))
        assertEquals("직선", r?.name)
        // 수평으로 보정됨
        assertEquals(r!!.pts[1], r.pts[3], 0.5f)
    }

    @Test fun rectangle() {
        val r = ShapeRecognizer.recognize(polyStroke(100f, 100f, 400f, 105f, 398f, 300f, 102f, 296f, 104f, 108f))
        assertEquals("사각형", r?.name)
    }

    @Test fun triangle() {
        val r = ShapeRecognizer.recognize(polyStroke(250f, 100f, 400f, 350f, 100f, 345f, 248f, 106f))
        assertEquals("삼각형", r?.name)
    }

    @Test fun circle() {
        val out = FloatBuf()
        for (i in 0..80) {
            val a = 2 * PI * i / 80
            out.add((300 + 150 * cos(a)).toFloat() + jitter(4f), (300 + 150 * sin(a)).toFloat() + jitter(4f))
        }
        assertEquals("원", ShapeRecognizer.recognize(out.toArray())?.name)
    }

    @Test fun scribbleIsNotAShape() {
        val out = FloatBuf()
        for (i in 0..200) out.add(100f + i * 2f, 200f + 80f * sin(i / 6.0).toFloat())
        assertNull(ShapeRecognizer.recognize(out.toArray()))
    }

    @Test fun angleOpenPolyline() {
        val r = ShapeRecognizer.recognize(polyStroke(100f, 100f, 100f, 400f, 400f, 402f))
        assertEquals("꺾은선", r?.name)
    }

    @Test fun arc() {
        val out = FloatBuf()
        for (i in 0..60) {
            val a = PI * i / 60   // 반원
            out.add((300 + 150 * cos(a)).toFloat() + jitter(3f), (300 - 150 * sin(a)).toFloat() + jitter(3f))
        }
        assertEquals("원호", ShapeRecognizer.recognize(out.toArray())?.name)
    }

    @Test fun parabolaBecomesSmoothCurve() {
        val out = FloatBuf()
        for (i in -40..40) {
            val x = i * 5f
            out.add(300 + x + jitter(2f), 100 + x * x / 100f + jitter(4f))
        }
        val r = ShapeRecognizer.recognize(out.toArray(), 50f)
        assertEquals("곡선", r?.name)
        assertEquals(out.x(0), r!!.pts[0], 0.01f)   // 끝점은 그대로
    }

    @Test fun smallStrokeNotCurved() {
        val out = FloatBuf()
        for (i in -10..10) out.add(30 + i * 1f + jitter(0.5f), 10 + i * i / 10f + jitter(0.5f))
        val r = ShapeRecognizer.recognize(out.toArray(), 200f)
        assert(r?.name != "곡선")
    }
}
