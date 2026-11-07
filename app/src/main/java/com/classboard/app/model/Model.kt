package com.classboard.app.model

import android.graphics.Color
import android.graphics.RectF
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

/** 획 종류 */
object StrokeKind {
    const val PEN = 0
    const val HIGHLIGHTER = 1
}

/**
 * 한 획. 좌표는 "페이지 좌표"(PDF 포인트 단위) 로 저장되므로 확대/축소와 무관하다.
 * pts = [x0, y0, x1, y1, ...]
 */
class Stroke(
    val kind: Int,
    val color: Int,
    val width: Float,
    val dashed: Boolean,
    val pts: FloatArray,
    val id: Long = nextId.incrementAndGet(),
) {
    val bounds: RectF = RectF().also { r ->
        if (pts.size >= 2) {
            r.set(pts[0], pts[1], pts[0], pts[1])
            var i = 2
            while (i < pts.size) {
                r.union(pts[i], pts[i + 1]); i += 2
            }
        }
        val h = width / 2f + 1f
        r.inset(-h, -h)
    }

    val pointCount get() = pts.size / 2

    fun withPoints(newPts: FloatArray) = Stroke(kind, color, width, dashed, newPts)

    fun toJson(): JSONObject = JSONObject().apply {
        put("k", kind)
        put("c", color)
        put("w", width.toDouble())
        if (dashed) put("d", true)
        val arr = JSONArray()
        // 소수점 1자리까지만 저장 (파일 크기 절약)
        for (v in pts) arr.put(Math.round(v * 10f))
        put("p", arr)
    }

    companion object {
        private val nextId = AtomicLong(1)

        fun fromJson(o: JSONObject): Stroke {
            val arr = o.getJSONArray("p")
            val pts = FloatArray(arr.length()) { arr.getInt(it) / 10f }
            return Stroke(
                o.optInt("k", StrokeKind.PEN),
                o.getInt("c"),
                o.getDouble("w").toFloat(),
                o.optBoolean("d", false),
                pts,
            )
        }
    }
}

/** 빈 페이지 배경 무늬 */
enum class Pattern(val label: String) {
    NONE("무지"),
    GRID("모눈"),
    COORD("좌표평면"),
    LINES("줄노트"),
    DOTS("점 격자"),
}

object BgColors {
    val WHITE = Color.WHITE
    val CHALK = Color.rgb(0x1F, 0x4E, 0x3D)   // 칠판 녹색
    val BLACK = Color.rgb(0x21, 0x25, 0x29)
    val all = listOf(WHITE to "흰색", CHALK to "칠판", BLACK to "검정")
}

/**
 * 한 페이지. pdfIndex >= 0 이면 PDF 의 해당 쪽, -1 이면 빈 판서 페이지.
 */
class Page(
    val pdfIndex: Int,
    val width: Float,
    val height: Float,
    var pattern: Pattern = Pattern.NONE,
    var bgColor: Int = Color.WHITE,
) {
    /** 현재 획 목록. 실행취소를 위해 항상 새 리스트로 교체하는 방식(불변처럼)으로 다룬다. */
    var strokes: List<Stroke> = emptyList()

    val isBlank get() = pdfIndex < 0

    /** 그리드/자 눈금의 기준 단위 (짧은 변을 20칸) */
    val unit get() = minOf(width, height) / 20f

    fun toJson(): JSONObject = JSONObject().apply {
        put("pdf", pdfIndex)
        put("w", width.toDouble())
        put("h", height.toDouble())
        put("pat", pattern.name)
        put("bg", bgColor)
        val arr = JSONArray()
        strokes.forEach { arr.put(it.toJson()) }
        put("strokes", arr)
    }

    companion object {
        const val BLANK_W = 1600f
        const val BLANK_H = 900f

        fun blank(pattern: Pattern = Pattern.NONE, bg: Int = Color.WHITE) =
            Page(-1, BLANK_W, BLANK_H, pattern, bg)

        fun fromJson(o: JSONObject): Page {
            val p = Page(
                o.getInt("pdf"),
                o.getDouble("w").toFloat(),
                o.getDouble("h").toFloat(),
                runCatching { Pattern.valueOf(o.optString("pat", "NONE")) }.getOrDefault(Pattern.NONE),
                o.optInt("bg", Color.WHITE),
            )
            val arr = o.optJSONArray("strokes") ?: JSONArray()
            p.strokes = List(arr.length()) { Stroke.fromJson(arr.getJSONObject(it)) }
            return p
        }
    }
}

/** 한 수업 자료(필기 노트). */
class NoteDocument(
    val key: String,
    var title: String,
    val hasPdf: Boolean,
    val pages: MutableList<Page>,
    var lastPage: Int = 0,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("version", 1)
        put("key", key)
        put("title", title)
        put("hasPdf", hasPdf)
        put("lastPage", lastPage)
        val arr = JSONArray()
        pages.forEach { arr.put(it.toJson()) }
        put("pages", arr)
    }

    companion object {
        fun fromJson(o: JSONObject): NoteDocument {
            val arr = o.getJSONArray("pages")
            val pages = MutableList(arr.length()) { Page.fromJson(arr.getJSONObject(it)) }
            return NoteDocument(
                o.getString("key"),
                o.optString("title", "제목 없음"),
                o.optBoolean("hasPdf", false),
                pages,
                o.optInt("lastPage", 0),
            )
        }
    }
}
