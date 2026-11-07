package com.classboard.app

import android.content.Context
import android.graphics.Color

/** 설정 값 (SharedPreferences) */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("classboard", Context.MODE_PRIVATE)

    private fun b(k: String, d: Boolean) = sp.getBoolean(k, d)
    private fun f(k: String, d: Float) = sp.getFloat(k, d)
    private fun i(k: String, d: Int) = sp.getInt(k, d)
    private fun put(block: android.content.SharedPreferences.Editor.() -> Unit) = sp.edit().apply(block).apply()

    /** 손바닥(넓은 접촉)으로 지우기 */
    var palmErase: Boolean get() = b("palmErase", true); set(v) = put { putBoolean("palmErase", v) }
    /** 이 값 이상의 접촉 크기는 손바닥으로 판단 (touchMajor 값) */
    var palmThreshold: Float get() = f("palmThreshold", 60f); set(v) = put { putFloat("palmThreshold", v) }

    /** 펜의 굵은 쪽(중간 접촉 크기)을 지우개로 사용 */
    var thickEraser: Boolean get() = b("thickEraser", false); set(v) = put { putBoolean("thickEraser", v) }
    var thickThreshold: Float get() = f("thickThreshold", 20f); set(v) = put { putFloat("thickThreshold", v) }

    /** 두 손가락으로 확대/이동 */
    var twoFingerZoom: Boolean get() = b("twoFingerZoom", true); set(v) = put { putBoolean("twoFingerZoom", v) }
    /** 그린 뒤 펜을 잠시 멈추면 도형으로 보정 */
    var holdToSnap: Boolean get() = b("holdToSnap", true); set(v) = put { putBoolean("holdToSnap", v) }
    /** 모든 획을 자동으로 도형 보정 */
    var autoShape: Boolean get() = b("autoShape", false); set(v) = put { putBoolean("autoShape", v) }

    /** 툴바 위치: 0 왼쪽, 1 가운데, 2 오른쪽 */
    var toolbarPos: Int get() = i("toolbarPos", 1); set(v) = put { putInt("toolbarPos", v) }

    var penColor: Int get() = i("penColor", Color.BLACK); set(v) = put { putInt("penColor", v) }
    var penWidth: Float get() = f("penWidth", 4f); set(v) = put { putFloat("penWidth", v) }
    var hlColor: Int get() = i("hlColor", Color.rgb(255, 212, 59)); set(v) = put { putInt("hlColor", v) }
    var hlWidth: Float get() = f("hlWidth", 24f); set(v) = put { putFloat("hlWidth", v) }
    var eraserRadius: Float get() = f("eraserRadius", 30f); set(v) = put { putFloat("eraserRadius", v) }
}
