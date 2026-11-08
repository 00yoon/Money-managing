package com.classboard.app.ink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class InkFilterTest {
    /** 천천히 수평으로 그을 때 IR 떨림(±3px)이 확실히 줄어드는지 */
    @Test fun reducesJitterOnSlowStroke() {
        val f = InkFilter().apply { strength = 1 }
        val rnd = Random(7)
        f.start(0f, 100f, 0)
        var rawDev = 0f; var filtDev = 0f
        for (i in 1..120) {
            val y = 100f + (rnd.nextFloat() - 0.5f) * 6f
            f.add(i * 2f, y, i * 8L)   // 250px/s
            if (i > 10) { rawDev += abs(y - 100f); filtDev += abs(f.y - 100f) }
        }
        assertTrue("filtered=$filtDev raw=$rawDev", filtDev < rawDev * 0.6f)
    }

    /** 펜이 멈추면 필터 위치가 실제 위치로 수렴 (끝이 짧아지지 않음) */
    @Test fun convergesWhenPenStops() {
        val f = InkFilter().apply { strength = 2 }
        f.start(0f, 0f, 0)
        for (i in 1..20) f.add(i * 20f, 0f, i * 8L)
        for (i in 21..60) f.add(400f, 0f, i * 8L)
        assertEquals(400f, f.x, 2f)
    }

    @Test fun offPassesThrough() {
        val f = InkFilter().apply { strength = 0 }
        f.start(0f, 0f, 0)
        f.add(13f, 7f, 8)
        assertEquals(13f, f.x, 0f); assertEquals(7f, f.y, 0f)
    }

    @Test fun predictionIsCapped() {
        val f = InkFilter().apply { strength = 0 }
        f.start(0f, 0f, 0)
        for (i in 1..10) f.add(i * 50f, 0f, i * 8L)  // 6250px/s
        val (px, _) = f.predict(18f, 36f)
        assertTrue(px > f.x && px - f.x <= 36.01f)
    }
}
