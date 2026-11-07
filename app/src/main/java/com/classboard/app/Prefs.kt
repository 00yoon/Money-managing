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

    /** 펜의 굵은 쪽(중간 접촉 크기) 동작: 0 일반 펜과 같음, 1 지우개, 2 두 번째 색 펜, 3 형광펜 */
    var thickAction: Int
        get() = i("thickAction", if (b("thickEraser", false)) 1 else 0)
        set(v) = put { putInt("thickAction", v) }
    /** 굵은 쪽으로 쓸 때의 색 (thickAction == 2) */
    var thickColor: Int get() = i("thickColor", Color.rgb(224, 49, 49)); set(v) = put { putInt("thickColor", v) }
    var thickThreshold: Float get() = f("thickThreshold", 20f); set(v) = put { putFloat("thickThreshold", v) }

    /** 두 손가락으로 확대/이동 */
    var twoFingerZoom: Boolean get() = b("twoFingerZoom", true); set(v) = put { putBoolean("twoFingerZoom", v) }
    /** 그린 뒤 펜을 잠시 멈추면 도형으로 보정 */
    var holdToSnap: Boolean get() = b("holdToSnap", true); set(v) = put { putBoolean("holdToSnap", v) }
    /** 모든 획을 자동으로 도형 보정 */
    var autoShape: Boolean get() = b("autoShape", false); set(v) = put { putBoolean("autoShape", v) }

    /** 손떨림 보정: 0 끔, 1 약하게, 2 강하게 */
    var smoothing: Int get() = i("smoothing", 1); set(v) = put { putInt("smoothing", v) }
    /** 펜 끝 위치 예측으로 지연 체감 줄이기 */
    var prediction: Boolean get() = b("prediction", true); set(v) = put { putBoolean("prediction", v) }
    /** 펜을 대고 0.5초 누르면 그 자리에 빠른 메뉴 (색/도구) */
    var longPressMenu: Boolean get() = b("longPressMenu", true); set(v) = put { putBoolean("longPressMenu", v) }
    /** 두 손가락으로 톡 치면 실행 취소 */
    var twoFingerTapUndo: Boolean get() = b("twoFingerTapUndo", true); set(v) = put { putBoolean("twoFingerTapUndo", v) }
    /** 지우개 도구: false 부분 지우기, true 획 전체 지우기 */
    var strokeEraser: Boolean get() = b("strokeEraser", false); set(v) = put { putBoolean("strokeEraser", v) }

    /** 툴바 위치: 0 왼쪽, 1 가운데, 2 오른쪽 */
    var toolbarPos: Int get() = i("toolbarPos", 1); set(v) = put { putInt("toolbarPos", v) }

    var penColor: Int get() = i("penColor", Color.BLACK); set(v) = put { putInt("penColor", v) }
    var penWidth: Float get() = f("penWidth", 4f); set(v) = put { putFloat("penWidth", v) }
    var hlColor: Int get() = i("hlColor", Color.rgb(255, 212, 59)); set(v) = put { putInt("hlColor", v) }
    var hlWidth: Float get() = f("hlWidth", 24f); set(v) = put { putFloat("hlWidth", v) }
    var eraserRadius: Float get() = f("eraserRadius", 30f); set(v) = put { putFloat("eraserRadius", v) }
}
