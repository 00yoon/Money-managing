package com.classboard.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import com.classboard.app.Prefs
import com.classboard.app.ink.FloatBuf
import com.classboard.app.ink.Geometry
import com.classboard.app.ink.ShapeRecognizer
import com.classboard.app.model.NoteDocument
import com.classboard.app.model.Page
import com.classboard.app.model.Pattern
import com.classboard.app.model.Stroke
import com.classboard.app.model.StrokeKind
import com.classboard.app.storage.PdfSource
import com.classboard.app.tools.Compass
import com.classboard.app.tools.MathTool
import com.classboard.app.tools.Protractor
import com.classboard.app.tools.Ruler
import com.classboard.app.tools.ToolDrag
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

enum class Tool { PEN, HIGHLIGHTER, ERASER, SHAPE }

enum class ShapeType(val label: String) {
    LINE("직선"), ARROW("화살표"), DASH_LINE("점선"), RECT("사각형"), ELLIPSE("원·타원"),
    TRIANGLE("삼각형"), RIGHT_TRIANGLE("직각삼각형"), AXES("좌표축"), NUMBER_LINE("수직선"),
}

/**
 * PDF 한 쪽과 필기를 그리고, 전자칠판 터치 입력을 처리하는 뷰.
 *
 * 레이어 구성 (아래→위)
 *  1. 페이지 배경 (흰색/칠판색 + 모눈 등)
 *  2. pdfFull   : 페이지 전체를 낮은 해상도로 렌더 (이동 중 빈 곳 방지)
 *  3. pdfScreen : 현재 화면 배율로 선명하게 렌더 (화면 크기 비트맵)
 *  4. ink       : 확정된 획을 화면 크기 비트맵에 캐시 (필기 속도 확보)
 *  5. 현재 그리는 획, 수학 도구, 지우개 커서
 */
class BoardView(context: Context) : View(context) {

    interface Listener {
        fun onPageChanged(index: Int, count: Int)
        fun onHistoryChanged(canUndo: Boolean, canRedo: Boolean)
        fun onDocChanged()
        fun onMessage(msg: String)
    }

    var listener: Listener? = null
    val prefs = Prefs(context)

    // ---------------- 도구 상태
    var tool = Tool.PEN
    var penColor = prefs.penColor
    var penWidthPx = prefs.penWidth
    var hlColor = prefs.hlColor
    var hlWidthPx = prefs.hlWidth
    var eraserRadiusPx = prefs.eraserRadius
    var dashed = false
    var shapeType = ShapeType.LINE

    val ruler = Ruler()
    val protractor = Protractor()
    val compass = Compass()
    private val mathTools: List<MathTool> = listOf(compass, protractor, ruler)

    /** 툴바가 가리는 아래쪽 높이 (페이지 맞춤 시 제외) */
    var bottomInset = 0
        set(v) { if (field != v) { field = v; if (doc != null) fitPage() } }

    var fitWidth = false
        private set

    // ---------------- 문서
    private var doc: NoteDocument? = null
    private var pdf: PdfSource? = null
    var pageIndex = 0
        private set
    val pageCount get() = doc?.pages?.size ?: 0
    val currentPage: Page? get() = doc?.pages?.getOrNull(pageIndex)

    // ---------------- 실행취소
    private class Op(val page: Page, val before: List<Stroke>, val after: List<Stroke>)
    private val undoStack = ArrayDeque<Op>()
    private val redoStack = ArrayDeque<Op>()

    // ---------------- 화면 변환 (페이지 좌표 → 화면 픽셀)
    private val viewMatrix = Matrix()
    private val inverse = Matrix()
    private var matrixVer = 0
    private var fitScale = 1f
    private val tmpPts = FloatArray(2)

    // ---------------- 레이어
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var renderGen = 0

    private var pdfFull: Bitmap? = null
    private var pdfFullScale = 1f
    private var pdfFullPage = -1

    private var pdfScreen: Bitmap? = null
    private var pdfScreenSpare: Bitmap? = null
    private val pdfScreenMatrixInv = Matrix()
    private var pdfScreenVer = -1
    private var pdfScreenPage = -1
    private var screenRenderPending = false

    private var ink: Bitmap? = null
    private var inkCanvas: Canvas? = null
    private var inkVer = -1
    private var inkPage = -1
    private var inkDirty = true

    // ---------------- 페인트
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val strokePaint = Paint()
    private val bgPaint = Paint()
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val axisText = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f; color = Color.argb(200, 80, 80, 80) }
    private val cursorFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(40, 120, 120, 120) }
    private val labelBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(220, 33, 37, 41) }
    private val labelText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER; textSize = 40f }
    private val shadowPaint = Paint().apply { color = Color.argb(60, 0, 0, 0) }
    private val path = Path()

    // ================================================================ 문서 열기 / 페이지 이동

    fun setDocument(d: NoteDocument, source: PdfSource?) {
        doc = d; pdf = source
        undoStack.clear(); redoStack.clear()
        pageIndex = d.lastPage.coerceIn(0, d.pages.size - 1)
        onPageSwitched()
    }

    fun goToPage(i: Int) {
        val d = doc ?: return
        if (i !in d.pages.indices || i == pageIndex) return
        cancelInput()
        pageIndex = i
        d.lastPage = i
        onPageSwitched()
    }

    private fun onPageSwitched() {
        pdfFull?.recycle(); pdfFull = null; pdfFullPage = -1
        pdfScreenPage = -1
        inkDirty = true
        if (width > 0) fitPage() else matrixVer++
        renderFull()
        listener?.onPageChanged(pageIndex, pageCount)
        notifyHistory()
        invalidate()
    }

    /** 현재 쪽 뒤에 빈 페이지 추가 */
    fun insertBlankPage(pattern: Pattern, bg: Int) {
        val d = doc ?: return
        val cur = currentPage
        val p = if (cur != null && !cur.isBlank) {
            // PDF 쪽과 같은 비율의 빈 페이지 (가로형 자료면 가로형)
            if (cur.width >= cur.height) Page.blank(pattern, bg) else Page(-1, Page.BLANK_H, Page.BLANK_W, pattern, bg)
        } else Page.blank(pattern, bg)
        d.pages.add(pageIndex + 1, p)
        listener?.onDocChanged()
        pageIndex++
        d.lastPage = pageIndex
        onPageSwitched()
    }

    /** 빈 페이지만 삭제 가능 (PDF 원본 쪽은 보호) */
    fun deleteCurrentBlankPage(): Boolean {
        val d = doc ?: return false
        val p = currentPage ?: return false
        if (!p.isBlank || d.pages.size <= 1) return false
        d.pages.removeAt(pageIndex)
        undoStack.removeAll { it.page === p }; redoStack.removeAll { it.page === p }
        pageIndex = pageIndex.coerceAtMost(d.pages.size - 1)
        d.lastPage = pageIndex
        listener?.onDocChanged()
        onPageSwitched()
        return true
    }

    fun setPageBackground(pattern: Pattern, bg: Int) {
        val p = currentPage ?: return
        if (!p.isBlank) return
        p.pattern = pattern; p.bgColor = bg
        listener?.onDocChanged()
        invalidate()
    }

    fun clearPage() {
        val p = currentPage ?: return
        if (p.strokes.isEmpty()) return
        applyChange(p, emptyList())
    }

    // ================================================================ 실행취소

    private fun applyChange(p: Page, after: List<Stroke>, record: Boolean = true) {
        val before = p.strokes
        p.strokes = after
        if (record) {
            undoStack.addLast(Op(p, before, after))
            while (undoStack.size > 200) undoStack.removeFirst()
            redoStack.clear()
        }
        inkDirty = true
        listener?.onDocChanged()
        notifyHistory()
        invalidate()
    }

    fun undo() {
        val op = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(op)
        op.page.strokes = op.before
        showOpPage(op.page)
    }

    fun redo() {
        val op = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(op)
        op.page.strokes = op.after
        showOpPage(op.page)
    }

    private fun showOpPage(p: Page) {
        val idx = doc?.pages?.indexOfFirst { it === p } ?: -1
        if (idx >= 0 && idx != pageIndex) goToPage(idx)
        inkDirty = true
        listener?.onDocChanged()
        notifyHistory()
        invalidate()
    }

    private fun notifyHistory() = listener?.onHistoryChanged(undoStack.isNotEmpty(), redoStack.isNotEmpty())

    // ================================================================ 화면 맞춤 / 확대

    fun setFitWidth(v: Boolean) { fitWidth = v; fitPage() }

    fun fitPage() {
        val p = currentPage ?: return
        val w = width.toFloat(); val h = (height - bottomInset).toFloat().coerceAtLeast(height * 0.5f)
        if (w <= 0 || h <= 0) return
        val margin = 8f
        val s = if (fitWidth) (w - margin * 2) / p.width else min((w - margin * 2) / p.width, (h - margin * 2) / p.height)
        fitScale = min((w - margin * 2) / p.width, (h - margin * 2) / p.height)
        viewMatrix.reset()
        viewMatrix.postScale(s, s)
        val tx = (w - p.width * s) / 2f
        val ty = if (fitWidth && p.height * s > h) margin else (h - p.height * s) / 2f
        viewMatrix.postTranslate(tx, ty)
        matrixChanged()
        requestScreenRender()
    }

    fun zoomBy(f: Float) {
        val cs = currentScale()
        val target = (cs * f).coerceIn(fitScale * 0.5f, fitScale * 8f)
        val k = target / cs
        viewMatrix.postScale(k, k, width / 2f, (height - bottomInset) / 2f)
        clampPan()
        matrixChanged()
        requestScreenRender()
    }

    private fun currentScale(): Float = viewMatrix.mapRadius(1f)

    private fun matrixChanged() {
        viewMatrix.invert(inverse)
        matrixVer++
        invalidate()
    }

    /** 페이지가 화면 밖으로 완전히 사라지지 않도록 */
    private fun clampPan() {
        val p = currentPage ?: return
        val r = RectF(0f, 0f, p.width, p.height)
        viewMatrix.mapRect(r)
        val keep = min(width, height) * 0.25f
        var dx = 0f; var dy = 0f
        if (r.right < keep) dx = keep - r.right
        if (r.left > width - keep) dx = width - keep - r.left
        if (r.bottom < keep) dy = keep - r.bottom
        if (r.top > height - keep) dy = height - keep - r.top
        if (dx != 0f || dy != 0f) viewMatrix.postTranslate(dx, dy)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        ink?.recycle(); ink = null; inkCanvas = null
        pdfScreen?.recycle(); pdfScreen = null
        pdfScreenSpare?.recycle(); pdfScreenSpare = null
        if (doc != null) { fitPage(); renderFull() }
    }

    // ================================================================ PDF 렌더링 (백그라운드)

    private fun renderFull() {
        val src = pdf ?: return
        val p = currentPage ?: return
        if (p.isBlank) return
        val idx = pageIndex
        val pdfIdx = p.pdfIndex
        val scale = min(1600f / max(p.width, p.height), 3f)
        val gen = ++renderGen
        io.execute {
            val bw = (p.width * scale).toInt().coerceAtLeast(1); val bh = (p.height * scale).toInt().coerceAtLeast(1)
            val bmp = try { Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888) } catch (e: OutOfMemoryError) { null } ?: return@execute
            bmp.eraseColor(Color.WHITE)
            val m = Matrix().apply { setScale(scale, scale) }
            val ok = runCatching { src.render(pdfIdx, bmp, m) }.getOrDefault(false)
            main.post {
                if (ok && idx == pageIndex && gen <= renderGen) {
                    pdfFull?.recycle(); pdfFull = bmp; pdfFullScale = scale; pdfFullPage = idx
                    invalidate()
                } else bmp.recycle()
            }
        }
    }

    private fun requestScreenRender() {
        val src = pdf ?: return
        val p = currentPage ?: return
        if (p.isBlank || width <= 0 || height <= 0) return
        if (screenRenderPending) { main.removeCallbacks(screenRenderRunnable) }
        screenRenderPending = true
        main.postDelayed(screenRenderRunnable, 60)
    }

    private val screenRenderRunnable = Runnable {
        screenRenderPending = false
        val src = pdf ?: return@Runnable
        val p = currentPage ?: return@Runnable
        if (p.isBlank) return@Runnable
        val w = width; val h = height
        var target = pdfScreenSpare
        pdfScreenSpare = null
        if (target == null || target.width != w || target.height != h) {
            target?.recycle()
            target = try { Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) } catch (e: OutOfMemoryError) { null } ?: return@Runnable
        }
        val m = Matrix(viewMatrix)
        val ver = matrixVer
        val idx = pageIndex
        val pdfIdx = p.pdfIndex
        val bmp = target
        io.execute {
            bmp.eraseColor(Color.WHITE)
            val ok = runCatching { src.render(pdfIdx, bmp, m) }.getOrDefault(false)
            main.post {
                if (ok && idx == pageIndex) {
                    pdfScreenSpare = pdfScreen
                    pdfScreen = bmp
                    m.invert(pdfScreenMatrixInv)
                    pdfScreenVer = ver; pdfScreenPage = idx
                    invalidate()
                } else {
                    pdfScreenSpare?.recycle(); pdfScreenSpare = bmp
                }
            }
        }
    }

    // ================================================================ 그리기

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.rgb(0x49, 0x50, 0x57))
        val p = currentPage ?: return

        val pageRect = RectF(0f, 0f, p.width, p.height)
        // 그림자
        val sr = RectF(pageRect); viewMatrix.mapRect(sr); sr.offset(6f, 6f)
        canvas.drawRect(sr, shadowPaint)

        canvas.save()
        canvas.concat(viewMatrix)
        canvas.clipRect(pageRect)
        drawBackground(canvas, p)
        if (!p.isBlank) {
            pdfFull?.takeIf { pdfFullPage == pageIndex }?.let { b ->
                canvas.save(); canvas.scale(1f / pdfFullScale, 1f / pdfFullScale)
                canvas.drawBitmap(b, 0f, 0f, bmpPaint); canvas.restore()
            }
            pdfScreen?.takeIf { pdfScreenPage == pageIndex }?.let { b ->
                canvas.save(); canvas.concat(pdfScreenMatrixInv)
                canvas.drawBitmap(b, 0f, 0f, if (pdfScreenVer == matrixVer) null else bmpPaint); canvas.restore()
            }
        }
        canvas.restore()

        // 확정된 잉크
        if (mode == Mode.GESTURE) {
            canvas.save(); canvas.concat(viewMatrix); canvas.clipRect(pageRect)
            for (s in p.strokes) drawStroke(canvas, s)
            canvas.restore()
        } else {
            ensureInk(p)
            ink?.let { canvas.drawBitmap(it, 0f, 0f, null) }
        }

        // 그리는 중인 획 / 미리보기
        canvas.save(); canvas.concat(viewMatrix)
        drawLive(canvas)
        val s = currentScale()
        for (t in mathTools.asReversed()) if (t.visible) t.draw(canvas, s)
        canvas.restore()

        // 지우개 커서 (화면 좌표)
        if (eraserCursorR > 0f) {
            canvas.drawCircle(eraserCursorX, eraserCursorY, eraserCursorR, cursorFill)
            canvas.drawCircle(eraserCursorX, eraserCursorY, eraserCursorR, cursorPaint)
        }
        // 측정값 라벨
        liveLabel?.let { drawLabel(canvas, it) }
    }

    private fun drawLabel(c: Canvas, s: String) {
        labelText.textSize = (resources.displayMetrics.density * 26f)
        val tw = labelText.measureText(s)
        val cx = width / 2f; val top = resources.displayMetrics.density * 16f
        val pad = labelText.textSize * 0.6f
        c.drawRoundRect(cx - tw / 2 - pad, top, cx + tw / 2 + pad, top + labelText.textSize + pad * 1.4f, pad, pad, labelBg)
        c.drawText(s, cx, top + pad * 0.7f + labelText.textSize * 0.85f, labelText)
    }

    private fun ensureInk(p: Page) {
        if (width <= 0 || height <= 0) return
        var b = ink
        if (b == null || b.width != width || b.height != height) {
            b?.recycle()
            b = try { Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888) } catch (e: OutOfMemoryError) { null } ?: return
            ink = b; inkCanvas = Canvas(b); inkDirty = true
        }
        if (!inkDirty && inkVer == matrixVer && inkPage == pageIndex) return
        b.eraseColor(Color.TRANSPARENT)
        val c = inkCanvas!!
        c.save()
        c.concat(viewMatrix)
        c.clipRect(0f, 0f, p.width, p.height)
        val vis = RectF(0f, 0f, width.toFloat(), height.toFloat()); inverse.mapRect(vis)
        for (s in p.strokes) if (RectF.intersects(vis, s.bounds)) drawStroke(c, s)
        c.restore()
        inkDirty = false; inkVer = matrixVer; inkPage = pageIndex
    }

    /** 새 획 하나만 잉크 캐시에 추가 (전체 다시 그리기 회피) */
    private fun appendToInk(s: Stroke) {
        val c = inkCanvas ?: run { inkDirty = true; return }
        if (inkDirty || inkVer != matrixVer || inkPage != pageIndex) { inkDirty = true; return }
        val p = currentPage ?: return
        c.save(); c.concat(viewMatrix); c.clipRect(0f, 0f, p.width, p.height)
        drawStroke(c, s)
        c.restore()
    }

    private fun drawStroke(c: Canvas, s: Stroke) {
        Geometry.configurePaint(strokePaint, s.kind, s.color, s.width, s.dashed)
        Geometry.buildPath(s.pts, s.pointCount, path)
        c.drawPath(path, strokePaint)
    }

    private fun drawBackground(c: Canvas, p: Page) {
        bgPaint.color = if (p.isBlank) p.bgColor else Color.WHITE
        c.drawRect(0f, 0f, p.width, p.height, bgPaint)
        if (!p.isBlank || p.pattern == Pattern.NONE) return
        val dark = Color.luminance(p.bgColor) < 0.4f
        val u = p.unit
        val s = currentScale()
        gridPaint.style = Paint.Style.STROKE
        gridPaint.strokeWidth = 1f / s
        gridPaint.color = if (dark) Color.argb(70, 255, 255, 255) else Color.argb(60, 30, 90, 160)
        when (p.pattern) {
            Pattern.GRID, Pattern.COORD -> {
                val ox = if (p.pattern == Pattern.COORD) p.width / 2f else 0f
                val oy = if (p.pattern == Pattern.COORD) p.height / 2f else 0f
                var x = ox % u; while (x <= p.width) { c.drawLine(x, 0f, x, p.height, gridPaint); x += u }
                var y = oy % u; while (y <= p.height) { c.drawLine(0f, y, p.width, y, gridPaint); y += u }
                if (p.pattern == Pattern.COORD) drawAxes(c, p, ox, oy, u, dark, s)
            }
            Pattern.LINES -> {
                gridPaint.strokeWidth = 1.5f / s
                var y = u * 1.5f; while (y <= p.height) { c.drawLine(0f, y, p.width, y, gridPaint); y += u * 1.5f }
            }
            Pattern.DOTS -> {
                gridPaint.style = Paint.Style.FILL
                gridPaint.color = if (dark) Color.argb(130, 255, 255, 255) else Color.argb(110, 30, 60, 100)
                val r = 2.2f / s
                var x = u; while (x < p.width) { var y = u; while (y < p.height) { c.drawCircle(x, y, r, gridPaint); y += u }; x += u }
            }
            Pattern.NONE -> {}
        }
    }

    private fun drawAxes(c: Canvas, p: Page, ox: Float, oy: Float, u: Float, dark: Boolean, s: Float) {
        val col = if (dark) Color.argb(220, 255, 255, 255) else Color.argb(220, 40, 40, 40)
        gridPaint.color = col; gridPaint.strokeWidth = 2.5f / s
        c.drawLine(0f, oy, p.width, oy, gridPaint)
        c.drawLine(ox, 0f, ox, p.height, gridPaint)
        val a = u * 0.45f
        c.drawLine(p.width, oy, p.width - a, oy - a * 0.6f, gridPaint); c.drawLine(p.width, oy, p.width - a, oy + a * 0.6f, gridPaint)
        c.drawLine(ox, 0f, ox - a * 0.6f, a, gridPaint); c.drawLine(ox, 0f, ox + a * 0.6f, a, gridPaint)
        axisText.color = col
        axisText.textSize = u * 0.42f
        var k = 1
        while (ox + k * u < p.width - u * 0.6f) {
            c.drawText("$k", ox + k * u, oy + u * 0.6f, axisText)
            c.drawText("-$k", ox - k * u, oy + u * 0.6f, axisText); k++
        }
        k = 1
        axisText.textAlign = Paint.Align.RIGHT
        while (oy - k * u > u * 0.8f) {
            c.drawText("$k", ox - u * 0.2f, oy - k * u + u * 0.15f, axisText)
            c.drawText("-$k", ox - u * 0.2f, oy + k * u + u * 0.15f, axisText); k++
        }
        c.drawText("O", ox - u * 0.2f, oy + u * 0.6f, axisText)
        axisText.textSize = u * 0.6f
        c.drawText("y", ox - u * 0.35f, u * 0.8f, axisText)
        axisText.textAlign = Paint.Align.CENTER
        c.drawText("x", p.width - u * 0.5f, oy - u * 0.35f, axisText)
    }

    private fun drawLive(c: Canvas) {
        val pts: FloatArray = when {
            toolDrag != null -> toolDrag?.points() ?: return
            mode == Mode.SHAPE -> shapePreview ?: return
            mode == Mode.DRAW && live.points > 0 -> { Geometry.configurePaint(strokePaint, liveKind, liveColor, liveWidth, dashed); Geometry.buildPath(live.data, live.points, path); c.drawPath(path, strokePaint); return }
            else -> return
        }
        if (toolDrag != null && tool == Tool.HIGHLIGHTER) Geometry.configurePaint(strokePaint, StrokeKind.HIGHLIGHTER, hlColor, hlWidthPx / currentScale(), false)
        else Geometry.configurePaint(strokePaint, StrokeKind.PEN, penColor, penWidthPage(), shapeDashed())
        Geometry.buildPath(pts, pts.size / 2, path)
        c.drawPath(path, strokePaint)
    }

    // ================================================================ 터치 입력

    private enum class Mode { IDLE, DRAW, ERASE, GESTURE, TOOL, SHAPE, IGNORE }

    private var mode = Mode.IDLE
    private var activeId = -1
    private val live = FloatBuf()
    private var liveKind = StrokeKind.PEN
    private var liveColor = Color.BLACK
    private var liveWidth = 4f
    private var downTime = 0L
    private var downX = 0f; private var downY = 0f
    private var lastSX = 0f; private var lastSY = 0f
    private var moveEvents = 0
    private var snapped = false
    private var liveLabel: String? = null

    private var toolDrag: ToolDrag? = null
    private var shapeStart = FloatArray(2)
    private var shapePreview: FloatArray? = null

    private var eraseBefore: List<Stroke>? = null
    private var eraserR = 0f
    private var eraserCursorX = 0f; private var eraserCursorY = 0f; private var eraserCursorR = 0f
    private var lastEraseX = 0f; private var lastEraseY = 0f

    // 두 손가락
    private val gStart = Matrix()
    private var g0x = 0f; private var g0y = 0f; private var gDist = 1f
    private var gId0 = -1; private var gId1 = -1

    // 멈춤 감지(도형 보정)
    private var holdAnchorX = 0f; private var holdAnchorY = 0f
    private val holdRunnable = Runnable { onHold() }

    private fun contactSize(e: MotionEvent, idx: Int): Float {
        val tm = e.getTouchMajor(idx)
        return if (tm > 0f) tm else e.getSize(idx) * 1000f
    }

    private fun toPage(sx: Float, sy: Float): FloatArray {
        tmpPts[0] = sx; tmpPts[1] = sy; inverse.mapPoints(tmpPts); return tmpPts
    }

    private fun penWidthPage() = penWidthPx / currentScale()
    private fun shapeDashed() = dashed || (mode == Mode.SHAPE && shapeType == ShapeType.DASH_LINE)

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (currentPage == null) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> onDown(e)
            MotionEvent.ACTION_POINTER_DOWN -> onPointerDown(e)
            MotionEvent.ACTION_MOVE -> onMove(e)
            MotionEvent.ACTION_POINTER_UP -> onPointerUp(e)
            MotionEvent.ACTION_UP -> { onUp(e, false); mode = Mode.IDLE }
            MotionEvent.ACTION_CANCEL -> { onUp(e, true); mode = Mode.IDLE }
        }
        return true
    }

    private fun onDown(e: MotionEvent) {
        activeId = e.getPointerId(0)
        val sx = e.x; val sy = e.y
        downTime = SystemClock.uptimeMillis()
        downX = sx; downY = sy; lastSX = sx; lastSY = sy
        moveEvents = 0; snapped = false
        val size = contactSize(e, 0)

        if (prefs.palmErase && size >= prefs.palmThreshold) { startErase(sx, sy, palmRadius(size)); return }
        if (prefs.thickEraser && size >= prefs.thickThreshold) { startErase(sx, sy, eraserRadiusPx); return }

        val pp = toPage(sx, sy)
        val px = pp[0]; val py = pp[1]
        // 수학 도구 조작이 우선
        val s = currentScale()
        for (t in mathTools) {
            if (!t.visible) continue
            val d = t.hit(px, py, s) ?: continue
            toolDrag = d; mode = Mode.TOOL
            d.move(px, py)
            liveLabel = d.label()
            invalidate(); return
        }

        when (tool) {
            Tool.ERASER -> startErase(sx, sy, eraserRadiusPx)
            Tool.SHAPE -> {
                mode = Mode.SHAPE
                shapeStart[0] = px; shapeStart[1] = py
                shapePreview = null
            }
            Tool.PEN, Tool.HIGHLIGHTER -> {
                mode = Mode.DRAW
                val hl = tool == Tool.HIGHLIGHTER
                liveKind = if (hl) StrokeKind.HIGHLIGHTER else StrokeKind.PEN
                liveColor = if (hl) hlColor else penColor
                liveWidth = (if (hl) hlWidthPx else penWidthPx) / s
                live.clear(); live.add(px, py)
                holdAnchorX = sx; holdAnchorY = sy
                scheduleHold()
            }
        }
        invalidate()
    }

    private fun palmRadius(size: Float) = (size * 0.6f).coerceIn(28f, 240f)

    private fun onPointerDown(e: MotionEvent) {
        when (mode) {
            Mode.DRAW, Mode.SHAPE, Mode.IDLE -> {
                // 막 시작한 획이면 두 손가락 제스처로 전환
                val young = SystemClock.uptimeMillis() - downTime < 300 || hypot(lastSX - downX, lastSY - downY) < 30f
                if (prefs.twoFingerZoom && young) {
                    live.clear(); shapePreview = null; main.removeCallbacks(holdRunnable)
                    startGesture(e)
                }
            }
            Mode.TOOL -> {
                // 도구 이동 중 두 번째 손가락은 무시
            }
            Mode.ERASE, Mode.GESTURE, Mode.IGNORE -> {}
        }
    }

    private fun startGesture(e: MotionEvent) {
        mode = Mode.GESTURE
        gId0 = e.getPointerId(0); gId1 = e.getPointerId(e.actionIndex.takeIf { it != 0 } ?: 1)
        val i0 = e.findPointerIndex(gId0); val i1 = e.findPointerIndex(gId1)
        if (i0 < 0 || i1 < 0) { mode = Mode.IGNORE; return }
        g0x = (e.getX(i0) + e.getX(i1)) / 2f; g0y = (e.getY(i0) + e.getY(i1)) / 2f
        gDist = hypot(e.getX(i0) - e.getX(i1), e.getY(i0) - e.getY(i1)).coerceAtLeast(1f)
        gStart.set(viewMatrix)
        invalidate()
    }

    private fun onMove(e: MotionEvent) {
        when (mode) {
            Mode.GESTURE -> {
                val i0 = e.findPointerIndex(gId0); val i1 = e.findPointerIndex(gId1)
                if (i0 < 0 || i1 < 0) return
                val mx = (e.getX(i0) + e.getX(i1)) / 2f; val my = (e.getY(i0) + e.getY(i1)) / 2f
                val d = hypot(e.getX(i0) - e.getX(i1), e.getY(i0) - e.getY(i1)).coerceAtLeast(1f)
                var k = d / gDist
                val startScale = gStart.mapRadius(1f)
                k = (startScale * k).coerceIn(fitScale * 0.5f, fitScale * 8f) / startScale
                viewMatrix.set(gStart)
                viewMatrix.postScale(k, k, g0x, g0y)
                viewMatrix.postTranslate(mx - g0x, my - g0y)
                matrixChanged()
            }
            Mode.DRAW -> moveDraw(e)
            Mode.ERASE -> {
                val idx = e.findPointerIndex(activeId).takeIf { it >= 0 } ?: return
                // 손바닥이 넓어지면 지우개도 커짐
                if (prefs.palmErase) {
                    val size = contactSize(e, idx)
                    if (size >= prefs.palmThreshold) eraserR = max(eraserR, palmRadius(size))
                }
                for (h in 0 until e.historySize) eraseAlong(e.getHistoricalX(idx, h), e.getHistoricalY(idx, h))
                eraseAlong(e.getX(idx), e.getY(idx))
            }
            Mode.TOOL -> {
                val idx = e.findPointerIndex(activeId).takeIf { it >= 0 } ?: return
                val pp = toPage(e.getX(idx), e.getY(idx))
                toolDrag?.move(pp[0], pp[1])
                liveLabel = toolDrag?.label()
                invalidate()
            }
            Mode.SHAPE -> {
                val idx = e.findPointerIndex(activeId).takeIf { it >= 0 } ?: return
                lastSX = e.getX(idx); lastSY = e.getY(idx)
                val pp = toPage(lastSX, lastSY)
                shapePreview = buildShape(shapeStart[0], shapeStart[1], pp[0], pp[1])
                invalidate()
            }
            else -> {}
        }
    }

    private fun moveDraw(e: MotionEvent) {
        val idx = e.findPointerIndex(activeId).takeIf { it >= 0 } ?: return
        moveEvents++
        // 처음 몇 이벤트 안에 접촉이 커지면 손바닥으로 전환
        if (moveEvents <= 6 && prefs.palmErase) {
            val size = contactSize(e, idx)
            if (size >= prefs.palmThreshold) {
                live.clear(); main.removeCallbacks(holdRunnable)
                startErase(e.getX(idx), e.getY(idx), palmRadius(size)); return
            }
        }
        if (snapped) return
        val minD = 1.2f
        for (h in 0 until e.historySize) addLivePoint(e.getHistoricalX(idx, h), e.getHistoricalY(idx, h), minD)
        addLivePoint(e.getX(idx), e.getY(idx), minD)
        if (hypot(lastSX - holdAnchorX, lastSY - holdAnchorY) > 10f) {
            holdAnchorX = lastSX; holdAnchorY = lastSY
            scheduleHold()
        }
        invalidate()
    }

    private fun addLivePoint(sx: Float, sy: Float, minD: Float) {
        if (abs(sx - lastSX) < minD && abs(sy - lastSY) < minD) return
        lastSX = sx; lastSY = sy
        val pp = toPage(sx, sy)
        live.add(pp[0], pp[1])
    }

    private fun scheduleHold() {
        main.removeCallbacks(holdRunnable)
        if (prefs.holdToSnap) main.postDelayed(holdRunnable, 650)
    }

    /** 펜을 멈추고 있으면 도형으로 보정 */
    private fun onHold() {
        if (mode != Mode.DRAW || snapped || live.points < 4) return
        val r = ShapeRecognizer.recognize(live.toArray()) ?: return
        live.set(r.pts)
        snapped = true
        liveLabel = r.name
        invalidate()
        main.postDelayed({ if (liveLabel == r.name) { liveLabel = null; invalidate() } }, 900)
    }

    private fun onPointerUp(e: MotionEvent) {
        val id = e.getPointerId(e.actionIndex)
        if (mode == Mode.GESTURE && (id == gId0 || id == gId1)) {
            endGesture()
            mode = Mode.IGNORE   // 남은 손가락으로 획이 그어지지 않도록
        } else if (id == activeId && mode != Mode.GESTURE) {
            onUp(e, false)
            mode = Mode.IGNORE
        }
    }

    private fun endGesture() {
        clampPan()
        matrixChanged()
        inkDirty = true
        requestScreenRender()
    }

    private fun onUp(e: MotionEvent, cancel: Boolean) {
        main.removeCallbacks(holdRunnable)
        val p = currentPage ?: return
        when (mode) {
            Mode.DRAW -> {
                if (!cancel && live.points >= 1) {
                    var pts = live.toArray()
                    if (!snapped && prefs.autoShape && pts.size >= 6) {
                        ShapeRecognizer.recognize(pts)?.let { pts = it.pts }
                    }
                    if (pts.size == 2) pts = floatArrayOf(pts[0], pts[1], pts[0] + 0.01f, pts[1])
                    commitStroke(p, Stroke(liveKind, liveColor, liveWidth, dashed && liveKind == StrokeKind.PEN, Geometry.densify(pts, max(2f / currentScale(), liveWidth * 0.5f))))
                }
                live.clear()
            }
            Mode.SHAPE -> {
                val pts = shapePreview
                if (!cancel && pts != null) {
                    commitStroke(p, Stroke(StrokeKind.PEN, penColor, penWidthPage(), shapeDashed(), Geometry.densify(pts, 3f / currentScale())))
                }
                shapePreview = null
            }
            Mode.TOOL -> {
                val d = toolDrag
                if (!cancel && d != null && d.draws) {
                    d.points()?.let {
                        val dense = Geometry.densify(it, 3f / currentScale())
                        // 형광펜 상태에서 자를 대고 그으면 형광펜 밑줄
                        val st = if (tool == Tool.HIGHLIGHTER) Stroke(StrokeKind.HIGHLIGHTER, hlColor, hlWidthPx / currentScale(), false, dense)
                        else Stroke(StrokeKind.PEN, penColor, penWidthPage(), dashed, dense)
                        commitStroke(p, st)
                    }
                }
                toolDrag = null
            }
            Mode.ERASE -> endErase()
            Mode.GESTURE -> endGesture()
            else -> {}
        }
        if (liveLabel != null && !snapped) liveLabel = null
        if (snapped) main.postDelayed({ liveLabel = null; invalidate() }, 600)
        invalidate()
    }

    private fun commitStroke(p: Page, s: Stroke) {
        val before = p.strokes
        p.strokes = before + s
        undoStack.addLast(Op(p, before, p.strokes))
        while (undoStack.size > 200) undoStack.removeFirst()
        redoStack.clear()
        appendToInk(s)
        listener?.onDocChanged()
        notifyHistory()
    }

    fun cancelInput() {
        main.removeCallbacks(holdRunnable)
        live.clear(); shapePreview = null; toolDrag = null; liveLabel = null
        if (mode == Mode.ERASE) endErase()
        mode = Mode.IDLE
        eraserCursorR = 0f
    }

    // ---------------- 지우개

    private fun startErase(sx: Float, sy: Float, r: Float) {
        mode = Mode.ERASE
        eraseBefore = currentPage?.strokes
        eraserR = r
        lastEraseX = sx; lastEraseY = sy
        eraseAt(sx, sy)
    }

    private fun eraseAlong(sx: Float, sy: Float) {
        val d = hypot(sx - lastEraseX, sy - lastEraseY)
        val step = eraserR * 0.5f
        if (d > step) {
            val n = (d / step).toInt()
            for (k in 1..n) {
                val t = k / (n + 1f)
                eraseAt(lastEraseX + (sx - lastEraseX) * t, lastEraseY + (sy - lastEraseY) * t, false)
            }
        }
        eraseAt(sx, sy)
        lastEraseX = sx; lastEraseY = sy
    }

    private fun eraseAt(sx: Float, sy: Float, cursor: Boolean = true) {
        val p = currentPage ?: return
        if (cursor) { eraserCursorX = sx; eraserCursorY = sy; eraserCursorR = eraserR }
        val pp = toPage(sx, sy)
        val r = eraserR / currentScale()
        var changed = false
        val out = ArrayList<Stroke>(p.strokes.size + 4)
        for (s in p.strokes) {
            val res = Geometry.erase(s, pp[0], pp[1], r)
            if (res == null) out += s else { out.addAll(res); changed = true }
        }
        if (changed) {
            p.strokes = out
            inkDirty = true
        }
        invalidate()
    }

    private fun endErase() {
        val p = currentPage
        val before = eraseBefore
        eraseBefore = null
        eraserCursorR = 0f
        if (p != null && before != null && before !== p.strokes) {
            undoStack.addLast(Op(p, before, p.strokes))
            redoStack.clear()
            listener?.onDocChanged()
            notifyHistory()
        }
    }

    // ---------------- 도형 도구

    private fun buildShape(x0: Float, y0: Float, x1In: Float, y1In: Float): FloatArray? {
        if (Geometry.distance(x0, y0, x1In, y1In) < 2f) return null
        val u = currentPage?.unit ?: 30f
        return when (shapeType) {
            ShapeType.LINE, ShapeType.DASH_LINE -> {
                val (x1, y1) = Geometry.snapAngle(x0, y0, x1In, y1In)
                liveLabel = null
                Geometry.line(x0, y0, x1, y1)
            }
            ShapeType.ARROW -> {
                val (x1, y1) = Geometry.snapAngle(x0, y0, x1In, y1In)
                Geometry.arrow(x0, y0, x1, y1, max(penWidthPage() * 4f, u * 0.5f))
            }
            ShapeType.RECT -> floatArrayOf(x0, y0, x1In, y0, x1In, y1In, x0, y1In, x0, y0)
            ShapeType.ELLIPSE -> {
                var rx = abs(x1In - x0) / 2f; var ry = abs(y1In - y0) / 2f
                if (min(rx, ry) / max(rx, ry).coerceAtLeast(0.01f) > 0.9f) { val r = (rx + ry) / 2; rx = r; ry = r }
                Geometry.ellipse((x0 + x1In) / 2f, (y0 + y1In) / 2f, rx, ry)
            }
            ShapeType.TRIANGLE -> floatArrayOf((x0 + x1In) / 2f, y0, x1In, y1In, x0, y1In, (x0 + x1In) / 2f, y0)
            ShapeType.RIGHT_TRIANGLE -> floatArrayOf(x0, y0, x0, y1In, x1In, y1In, x0, y0)
            ShapeType.AXES -> {
                // 드래그한 사각형 안에 x, y 축 (중앙이 원점)
                val l = min(x0, x1In); val r = max(x0, x1In); val t = min(y0, y1In); val b = max(y0, y1In)
                val cx = (l + r) / 2f; val cy = (t + b) / 2f
                val h = max(penWidthPage() * 4f, u * 0.4f)
                val xa = Geometry.arrow(l, cy, r, cy, h)
                val ya = Geometry.arrow(cx, b, cx, t, h)
                // 하나의 연속 획: x축 → 원점으로 → y축 (이어그리기)
                val out = FloatBuf()
                for (i in xa.indices step 2) out.add(xa[i], xa[i + 1])
                out.add(r, cy); out.add(cx, cy)
                for (i in ya.indices step 2) out.add(ya[i], ya[i + 1])
                out.toArray()
            }
            ShapeType.NUMBER_LINE -> {
                // 수평 화살표 + 1단위 눈금
                val l = min(x0, x1In); val r = max(x0, x1In)
                val y = y0
                val h = max(penWidthPage() * 4f, u * 0.4f)
                val out = FloatBuf()
                out.add(l, y)
                var x = l + u
                while (x < r - u * 0.5f) {
                    out.add(x, y); out.add(x, y - u * 0.25f); out.add(x, y + u * 0.25f); out.add(x, y)
                    x += u
                }
                val a = Geometry.arrow(l, y, r, y, h)
                for (i in 2 until a.size step 2) out.add(a[i], a[i + 1])
                out.toArray()
            }
        }
    }

    // ================================================================ 도구 보이기

    /** 수학 도구를 현재 화면 가운데에 띄우거나 숨긴다 */
    fun toggleTool(t: MathTool): Boolean {
        if (t.visible) { t.visible = false; invalidate(); return false }
        val p = currentPage ?: return false
        val pp = toPage(width / 2f, (height - bottomInset) / 2f)
        t.placeAt(pp[0], pp[1], p.unit)
        t.visible = true
        invalidate()
        return true
    }

    // ================================================================ 썸네일

    /** 페이지 미리보기 (페이지 목록 화면용, 백그라운드 스레드에서 호출) */
    fun renderThumbnail(index: Int, maxW: Int): Bitmap? {
        val d = doc ?: return null
        val p = d.pages.getOrNull(index) ?: return null
        val s = maxW / p.width
        val bw = maxW; val bh = (p.height * s).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(if (p.isBlank) p.bgColor else Color.WHITE)
        val m = Matrix().apply { setScale(s, s) }
        if (!p.isBlank) runCatching { pdf?.render(p.pdfIndex, bmp, m) }
        val c = Canvas(bmp)
        c.concat(m)
        val paint = Paint()
        val pth = Path()
        for (st in p.strokes) {
            Geometry.configurePaint(paint, st.kind, st.color, max(st.width, 1.5f / s), st.dashed)
            Geometry.buildPath(st.pts, st.pointCount, pth)
            c.drawPath(pth, paint)
        }
        return bmp
    }

    fun pageLabel(index: Int): String {
        val p = doc?.pages?.getOrNull(index) ?: return ""
        return if (p.isBlank) "빈 페이지" else "${p.pdfIndex + 1}쪽"
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        io.shutdown()
    }
}
