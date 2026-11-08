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
import com.classboard.app.ink.InkFilter
import com.classboard.app.ink.ShapeRecognizer
import com.classboard.app.model.NoteDocument
import com.classboard.app.model.Page
import com.classboard.app.model.Pattern
import com.classboard.app.model.Stroke
import com.classboard.app.model.StrokeKind
import com.classboard.app.storage.PdfSource
import java.util.WeakHashMap
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

enum class Tool { PEN, HIGHLIGHTER, ERASER }

/**
 * PDF 한 쪽과 필기를 그리고, 전자칠판 터치 입력을 처리하는 뷰.
 *
 * 필기 반응 속도를 위해 화면을 두 겹으로 나눈다.
 *  - BoardView(정적 레이어, 하드웨어 레이어로 캐시): 배경, PDF, 확정된 필기
 *  - overlay(실시간 레이어): 지금 그리는 획, 도형 미리보기, 수학 도구, 지우개 커서
 * 펜이 움직일 때는 overlay 만 다시 그리므로 PDF/필기 비트맵을 매 프레임 다시 합성하지 않는다.
 *
 * 정적 레이어 구성 (아래→위)
 *  1. 페이지 배경 (흰색/칠판색 + 모눈 등)
 *  2. pdfFull   : 페이지 전체를 낮은 해상도로 렌더 (이동 중 빈 곳 방지)
 *  3. pdfScreen : 현재 화면 배율로 선명하게 렌더 (화면 크기 비트맵)
 *  4. ink       : 확정된 획을 화면 크기 비트맵에 캐시
 *  5. pending   : 방금 확정된 획 (잠시 벡터로 그리다가 쉴 때 ink 비트맵에 합침)
 */
class BoardView(context: Context) : View(context) {

    interface Listener {
        fun onPageChanged(index: Int, count: Int)
        fun onHistoryChanged(canUndo: Boolean, canRedo: Boolean)
        fun onDocChanged()
        fun onMessage(msg: String)
        /** 펜을 대고 길게 누름 → 그 자리에 빠른 메뉴 */
        fun onQuickMenu(x: Float, y: Float)
        /** 판 위에 펜/손이 닿음 (툴바 자동 접기용) */
        fun onTouchStart()
    }

    var listener: Listener? = null
    val prefs = Prefs(context)

    /** 실시간 레이어. 액티비티가 BoardView 바로 위에 붙인다 (터치는 통과). */
    val overlay: View = object : View(context) {
        override fun onDraw(canvas: Canvas) = drawOverlay(canvas)
    }

    // ---------------- 도구 상태
    var tool = Tool.PEN
    var penColor = prefs.penColor
    var penWidthPx = prefs.penWidth
    var hlColor = prefs.hlColor
    var hlWidthPx = prefs.hlWidth
    var eraserRadiusPx = prefs.eraserRadius
    var dashed = false

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
    val canUndo get() = undoStack.isNotEmpty()

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
    /** ink 비트맵에 아직 합치지 않은 최근 획 */
    private val pending = ArrayList<Stroke>()
    private val flushRunnable = Runnable { flushPending() }

    /** 획별 Path 캐시 (매 프레임 Path 를 새로 만들지 않도록) */
    private val pathCache = WeakHashMap<Stroke, Path>()

    // ---------------- 페인트
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val strokePaint = Paint()
    private val livePaint = Paint()
    private val bgPaint = Paint()
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val axisText = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f; color = Color.argb(200, 80, 80, 80) }
    private val cursorFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(40, 120, 120, 120) }
    private val labelBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(220, 33, 37, 41) }
    private val labelText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER; textSize = 40f }
    private val shadowPaint = Paint().apply { color = Color.argb(60, 0, 0, 0) }
    private val path = Path()
    private val livePath = Path()
    private val tailPath = Path()

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
        overlay.isClickable = false
        overlay.isFocusable = false
    }

    private fun invalidateLive() = overlay.invalidate()
    private fun invalidateAll() { invalidate(); overlay.invalidate() }

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
        inkDirty = true; pending.clear()
        if (width > 0) fitPage() else matrixVer++
        renderFull()
        listener?.onPageChanged(pageIndex, pageCount)
        notifyHistory()
        invalidateAll()
    }

    /** 현재 쪽 뒤에 빈 페이지 추가 */
    fun insertBlankPage(pattern: Pattern, bg: Int) {
        val d = doc ?: return
        val cur = currentPage
        val p = if (cur != null && !cur.isBlank) {
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

    /** 현재 페이지 전체 지우기 (실행 취소 가능) */
    fun clearPage() {
        val p = currentPage ?: return
        if (p.strokes.isEmpty()) return
        val before = p.strokes
        p.strokes = emptyList()
        pushOp(Op(p, before, p.strokes))
        strokesReplaced()
    }

    // ================================================================ 실행취소

    private fun pushOp(op: Op) {
        undoStack.addLast(op)
        while (undoStack.size > 300) undoStack.removeFirst()
        redoStack.clear()
        listener?.onDocChanged()
        notifyHistory()
    }

    /** 획 목록이 통째로 바뀜 → 잉크 캐시 다시 만들기 */
    private fun strokesReplaced() {
        inkDirty = true; pending.clear()
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
        strokesReplaced()
        listener?.onDocChanged()
        notifyHistory()
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
        invalidateAll()
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
        if (pdf == null) return
        val p = currentPage ?: return
        if (p.isBlank || width <= 0 || height <= 0) return
        if (screenRenderPending) main.removeCallbacks(screenRenderRunnable)
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

    // ================================================================ 정적 레이어 그리기

    /** 지우는 중/이동 중에는 잉크 비트맵 대신 벡터로 직접 그린다 */
    private val vectorInk get() = mode == Mode.GESTURE || mode == Mode.ERASE

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.rgb(0x49, 0x50, 0x57))
        val p = currentPage ?: return

        val pageRect = RectF(0f, 0f, p.width, p.height)
        val sr = RectF(pageRect); viewMatrix.mapRect(sr); sr.offset(6f, 6f)
        canvas.drawRect(sr, shadowPaint)

        canvas.save()
        canvas.concat(viewMatrix)
        canvas.clipRect(pageRect)
        val sharp = !p.isBlank && pdfScreen != null && pdfScreenPage == pageIndex && pdfScreenVer == matrixVer
        if (sharp) {
            // 선명한 렌더가 화면과 정확히 맞으면 그것만 그린다 (겹쳐 그리기 최소화)
            canvas.save(); canvas.concat(pdfScreenMatrixInv)
            canvas.drawBitmap(pdfScreen!!, 0f, 0f, null); canvas.restore()
        } else {
            drawBackground(canvas, p)
            if (!p.isBlank) {
                pdfFull?.takeIf { pdfFullPage == pageIndex }?.let { b ->
                    canvas.save(); canvas.scale(1f / pdfFullScale, 1f / pdfFullScale)
                    canvas.drawBitmap(b, 0f, 0f, bmpPaint); canvas.restore()
                }
                pdfScreen?.takeIf { pdfScreenPage == pageIndex }?.let { b ->
                    canvas.save(); canvas.concat(pdfScreenMatrixInv)
                    canvas.drawBitmap(b, 0f, 0f, bmpPaint); canvas.restore()
                }
            }
        }
        canvas.restore()

        if (vectorInk) {
            canvas.save(); canvas.concat(viewMatrix); canvas.clipRect(pageRect)
            val vis = visiblePageRect()
            for (s in p.strokes) if (RectF.intersects(vis, s.bounds)) drawStroke(canvas, s)
            canvas.restore()
        } else {
            ensureInk(p)
            ink?.let { canvas.drawBitmap(it, 0f, 0f, null) }
            if (pending.isNotEmpty()) {
                canvas.save(); canvas.concat(viewMatrix); canvas.clipRect(pageRect)
                for (s in pending) drawStroke(canvas, s)
                canvas.restore()
            }
        }
    }

    private fun visiblePageRect(): RectF = RectF(0f, 0f, width.toFloat(), height.toFloat()).also { inverse.mapRect(it) }

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
        val vis = visiblePageRect()
        for (s in p.strokes) if (RectF.intersects(vis, s.bounds)) drawStroke(c, s)
        c.restore()
        inkDirty = false; inkVer = matrixVer; inkPage = pageIndex
        pending.clear()   // 전체 다시 그리기에 이미 포함됨
    }

    /** 쉬는 동안 최근 획들을 ink 비트맵에 합친다 (비트맵 업로드를 필기 중에 하지 않기 위해) */
    private fun flushPending() {
        if (pending.isEmpty()) return
        if (mode != Mode.IDLE) { main.postDelayed(flushRunnable, 300); return }
        val c = inkCanvas
        val p = currentPage
        if (c != null && p != null && !inkDirty && inkVer == matrixVer && inkPage == pageIndex) {
            c.save(); c.concat(viewMatrix); c.clipRect(0f, 0f, p.width, p.height)
            for (s in pending) drawStroke(c, s)
            c.restore()
        } else inkDirty = true
        pending.clear()
        invalidate()
    }

    private fun pathOf(s: Stroke): Path = pathCache.getOrPut(s) { Geometry.buildPath(s.pts, s.pointCount, Path()) }

    private fun drawStroke(c: Canvas, s: Stroke) {
        Geometry.configurePaint(strokePaint, s.kind, s.color, s.width, s.dashed)
        c.drawPath(pathOf(s), strokePaint)
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

    // ================================================================ 실시간 레이어 그리기

    private fun drawOverlay(canvas: Canvas) {
        val p = currentPage ?: return
        canvas.save()
        canvas.concat(viewMatrix)
        canvas.save()
        canvas.clipRect(0f, 0f, p.width, p.height)
        drawLive(canvas)
        canvas.restore()
        canvas.restore()

        if (eraserCursorR > 0f) {
            canvas.drawCircle(eraserCursorX, eraserCursorY, eraserCursorR, cursorFill)
            canvas.drawCircle(eraserCursorX, eraserCursorY, eraserCursorR, cursorPaint)
        }
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

    private fun drawLive(c: Canvas) {
        if (mode == Mode.DRAW && live.points > 0) {
            Geometry.configurePaint(livePaint, liveKind, liveColor, liveWidth, liveDashed)
            if (liveKind == StrokeKind.HIGHLIGHTER || liveDashed || snapped) {
                // 반투명/점선은 한 경로로 그려야 겹침 얼룩이 없다
                Geometry.buildPath(live.data, live.points, path)
                c.drawPath(path, livePaint)
            } else {
                c.drawPath(livePath, livePaint)
                // 마지막 중간점 → 마지막 점 → 예측 위치
                val n = live.points
                tailPath.rewind()
                // livePath 는 n>=3 일 때 마지막 중간점까지 이어져 있다 (buildPath 와 동일)
                if (n >= 3) tailPath.moveTo((live.x(n - 2) + live.x(n - 1)) / 2f, (live.y(n - 2) + live.y(n - 1)) / 2f)
                else tailPath.moveTo(live.x(0), live.y(0))
                tailPath.lineTo(live.x(n - 1) + if (n == 1) 0.01f else 0f, live.y(n - 1))
                if (hasPrediction) tailPath.lineTo(predX, predY)
                c.drawPath(tailPath, livePaint)
            }
        }
    }

    // ================================================================ 터치 입력

    private enum class Mode { IDLE, DRAW, ERASE, GESTURE, SWIPE, IGNORE }

    private var mode = Mode.IDLE
        set(v) {
            if (field == v) return
            val wasVector = field == Mode.GESTURE || field == Mode.ERASE
            field = v
            val isVector = v == Mode.GESTURE || v == Mode.ERASE
            // 이동/지우기 중에는 정적 레이어가 매 프레임 바뀌므로 하드웨어 레이어를 잠시 끈다
            if (isVector != wasVector) setLayerType(if (isVector) LAYER_TYPE_NONE else LAYER_TYPE_HARDWARE, null)
        }
    private var activeId = -1
    private val live = FloatBuf()
    private val filter = InkFilter()
    private var liveKind = StrokeKind.PEN
    private var liveColor = Color.BLACK
    private var liveWidth = 4f
    private var liveDashed = false
    private var downTime = 0L
    private var downX = 0f; private var downY = 0f
    private var lastSX = 0f; private var lastSY = 0f
    private var maxTravel = 0f
    private var moveEvents = 0
    private var snapped = false
    private var liveLabel: String? = null
    private var hasPrediction = false
    private var predX = 0f; private var predY = 0f

    // 세·네 손가락 쪽 넘기기
    private var swipeStartX = 0f; private var swipeStartY = 0f
    private var swipeDone = false

    private var eraseBefore: List<Stroke>? = null
    private var eraseWhole = false
    private var eraserR = 0f
    private var eraserCursorX = 0f; private var eraserCursorY = 0f; private var eraserCursorR = 0f
    private var lastEraseX = 0f; private var lastEraseY = 0f

    // 두 손가락
    private val gStart = Matrix()
    private var g0x = 0f; private var g0y = 0f; private var gDist = 1f
    private var gId0 = -1; private var gId1 = -1
    private var gTime = 0L
    private var gMoved = 0f

    // 멈춤 감지(도형 보정), 길게 누르기(빠른 메뉴)
    private var holdAnchorX = 0f; private var holdAnchorY = 0f
    private val holdRunnable = Runnable { onHold() }
    private val longPressRunnable = Runnable { onLongPress() }

    private fun contactSize(e: MotionEvent, idx: Int): Float {
        val tm = e.getTouchMajor(idx)
        return if (tm > 0f) tm else e.getSize(idx) * 1000f
    }

    private fun toPage(sx: Float, sy: Float): FloatArray {
        tmpPts[0] = sx; tmpPts[1] = sy; inverse.mapPoints(tmpPts); return tmpPts
    }


    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (currentPage == null) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> onDown(e)
            MotionEvent.ACTION_POINTER_DOWN -> onPointerDown(e)
            MotionEvent.ACTION_MOVE -> onMove(e)
            MotionEvent.ACTION_POINTER_UP -> onPointerUp(e)
            MotionEvent.ACTION_UP -> { onUp(e, false); mode = Mode.IDLE; scheduleFlush() }
            MotionEvent.ACTION_CANCEL -> { onUp(e, true); mode = Mode.IDLE; scheduleFlush() }
        }
        return true
    }

    private fun scheduleFlush() {
        main.removeCallbacks(flushRunnable)
        if (pending.isNotEmpty()) main.postDelayed(flushRunnable, 350)
    }

    private fun onDown(e: MotionEvent) {
        main.removeCallbacks(flushRunnable)
        activeId = e.getPointerId(0)
        val sx = e.x; val sy = e.y
        downTime = SystemClock.uptimeMillis()
        downX = sx; downY = sy; lastSX = sx; lastSY = sy
        maxTravel = 0f; moveEvents = 0; snapped = false; hasPrediction = false
        listener?.onTouchStart()
        val size = contactSize(e, 0)

        if (prefs.palmErase && size >= prefs.palmThreshold) { startErase(sx, sy, palmRadius(size), false); return }

        // 펜의 굵은 쪽
        val thick = prefs.thickAction != 0 && size >= prefs.thickThreshold
        if (thick && prefs.thickAction == 1) { startErase(sx, sy, eraserRadiusPx, prefs.strokeEraser); scheduleLongPress(); return }

        val pp = toPage(sx, sy)
        val px = pp[0]; val py = pp[1]
        val s = currentScale()

        val effTool = when {
            thick && prefs.thickAction == 2 -> Tool.PEN
            thick && prefs.thickAction == 3 -> Tool.HIGHLIGHTER
            else -> tool
        }
        when (effTool) {
            Tool.ERASER -> startErase(sx, sy, eraserRadiusPx, prefs.strokeEraser)
            Tool.PEN, Tool.HIGHLIGHTER -> {
                mode = Mode.DRAW
                val hl = effTool == Tool.HIGHLIGHTER
                liveKind = if (hl) StrokeKind.HIGHLIGHTER else StrokeKind.PEN
                liveColor = when { hl -> hlColor; thick -> prefs.thickColor; else -> penColor }
                liveWidth = (if (hl) hlWidthPx else penWidthPx) / s
                liveDashed = dashed && !hl
                filter.strength = prefs.smoothing
                filter.start(sx, sy, e.eventTime)
                live.clear(); live.add(px, py)
                livePath.rewind(); livePath.moveTo(px, py)
                holdAnchorX = sx; holdAnchorY = sy
                scheduleHold()
            }
        }
        scheduleLongPress()
        invalidateLive()
    }

    private fun scheduleLongPress() {
        main.removeCallbacks(longPressRunnable)
        if (prefs.longPressMenu) main.postDelayed(longPressRunnable, 520)
    }

    /** 펜을 대고 움직이지 않고 누르고 있으면 → 그 자리에 빠른 메뉴 */
    private fun onLongPress() {
        if (maxTravel > 14f) return
        if (mode != Mode.DRAW && mode != Mode.ERASE) return
        main.removeCallbacks(holdRunnable)
        if (mode == Mode.ERASE) endErase()
        live.clear(); livePath.rewind(); hasPrediction = false
        mode = Mode.IGNORE
        invalidateAll()
        listener?.onQuickMenu(downX, downY)
    }

    private fun palmRadius(size: Float) = (size * 0.6f).coerceIn(28f, 240f)

    private fun onPointerDown(e: MotionEvent) {
        // 세 손가락 이상 → 쪽 넘기기 (두 손가락 제스처 중이거나 막 시작한 획이어도)
        if (e.pointerCount >= 3 && mode != Mode.ERASE && mode != Mode.SWIPE) {
            val young = SystemClock.uptimeMillis() - downTime < 400 || maxTravel < 40f
            if (mode == Mode.GESTURE || young) { startSwipe(e); return }
        }
        when (mode) {
            Mode.DRAW, Mode.IDLE -> {
                // 막 시작한 획이면 두 손가락 제스처로 전환
                val young = SystemClock.uptimeMillis() - downTime < 300 || maxTravel < 30f
                if ((prefs.twoFingerZoom || prefs.twoFingerTapUndo) && young) {
                    main.removeCallbacks(holdRunnable); main.removeCallbacks(longPressRunnable)
                    live.clear(); livePath.rewind(); hasPrediction = false
                    startGesture(e)
                }
            }
            Mode.ERASE, Mode.GESTURE, Mode.SWIPE, Mode.IGNORE -> {}
        }
    }

    private fun startSwipe(e: MotionEvent) {
        main.removeCallbacks(holdRunnable); main.removeCallbacks(longPressRunnable)
        if (mode == Mode.GESTURE) { viewMatrix.set(gStart); matrixChanged() }   // 확대 시작한 것 되돌리기
        live.clear(); livePath.rewind(); hasPrediction = false
        mode = Mode.SWIPE
        swipeDone = false
        swipeStartX = avgX(e); swipeStartY = avgY(e)
        invalidateAll()
    }

    private fun avgX(e: MotionEvent): Float { var t = 0f; for (i in 0 until e.pointerCount) t += e.getX(i); return t / e.pointerCount }
    private fun avgY(e: MotionEvent): Float { var t = 0f; for (i in 0 until e.pointerCount) t += e.getY(i); return t / e.pointerCount }

    private fun onSwipeMove(e: MotionEvent) {
        if (swipeDone) return
        val dx = avgX(e) - swipeStartX; val dy = avgY(e) - swipeStartY
        val need = min(width, height) * 0.08f
        if (abs(dx) > need && abs(dx) > abs(dy) * 1.2f) {
            swipeDone = true   // 한 번 쓸 때 한 쪽만
            val target = if (dx < 0) pageIndex + 1 else pageIndex - 1
            if (target in 0 until pageCount) {
                goToPage(target)
                mode = Mode.SWIPE   // goToPage 의 cancelInput 이 IDLE 로 바꾼 것 복원 (손 뗄 때까지 무시)
                flashLabel("${pageIndex + 1} / $pageCount")
            } else flashLabel(if (dx < 0) "마지막 쪽입니다" else "첫 쪽입니다")
        }
    }

    private fun flashLabel(text: String) {
        liveLabel = text
        invalidateLive()
        main.postDelayed({ if (liveLabel == text) { liveLabel = null; invalidateLive() } }, 800)
    }

    private fun startGesture(e: MotionEvent) {
        mode = Mode.GESTURE
        gId0 = e.getPointerId(0); gId1 = e.getPointerId(e.actionIndex.takeIf { it != 0 } ?: 1)
        val i0 = e.findPointerIndex(gId0); val i1 = e.findPointerIndex(gId1)
        if (i0 < 0 || i1 < 0) { mode = Mode.IGNORE; return }
        g0x = (e.getX(i0) + e.getX(i1)) / 2f; g0y = (e.getY(i0) + e.getY(i1)) / 2f
        gDist = hypot(e.getX(i0) - e.getX(i1), e.getY(i0) - e.getY(i1)).coerceAtLeast(1f)
        gStart.set(viewMatrix)
        gTime = SystemClock.uptimeMillis(); gMoved = 0f
        invalidateAll()
    }

    private fun onMove(e: MotionEvent) {
        val idxA = e.findPointerIndex(activeId)
        if (idxA >= 0) maxTravel = max(maxTravel, hypot(e.getX(idxA) - downX, e.getY(idxA) - downY))
        if (maxTravel > 14f) main.removeCallbacks(longPressRunnable)
        when (mode) {
            Mode.GESTURE -> {
                val i0 = e.findPointerIndex(gId0); val i1 = e.findPointerIndex(gId1)
                if (i0 < 0 || i1 < 0) return
                val mx = (e.getX(i0) + e.getX(i1)) / 2f; val my = (e.getY(i0) + e.getY(i1)) / 2f
                val d = hypot(e.getX(i0) - e.getX(i1), e.getY(i0) - e.getY(i1)).coerceAtLeast(1f)
                gMoved = max(gMoved, max(hypot(mx - g0x, my - g0y), abs(d - gDist)))
                if (!prefs.twoFingerZoom) return
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
                val idx = idxA.takeIf { it >= 0 } ?: return
                if (prefs.palmErase && !eraseWhole) {
                    val size = contactSize(e, idx)
                    if (size >= prefs.palmThreshold) eraserR = max(eraserR, palmRadius(size))
                }
                for (h in 0 until e.historySize) eraseAlong(e.getHistoricalX(idx, h), e.getHistoricalY(idx, h))
                eraseAlong(e.getX(idx), e.getY(idx))
            }
            Mode.SWIPE -> onSwipeMove(e)
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
                live.clear(); livePath.rewind(); hasPrediction = false
                main.removeCallbacks(holdRunnable); main.removeCallbacks(longPressRunnable)
                startErase(e.getX(idx), e.getY(idx), palmRadius(size), false)
                invalidateLive(); return
            }
        }
        if (snapped) return
        for (h in 0 until e.historySize) {
            filter.add(e.getHistoricalX(idx, h), e.getHistoricalY(idx, h), e.getHistoricalEventTime(h))
            addLivePoint(filter.x, filter.y)
        }
        filter.add(e.getX(idx), e.getY(idx), e.eventTime)
        addLivePoint(filter.x, filter.y)
        if (prefs.prediction && liveKind == StrokeKind.PEN) {
            val (ax, ay) = filter.predict(18f, 36f)
            val pp = toPage(ax, ay); predX = pp[0]; predY = pp[1]; hasPrediction = true
        } else hasPrediction = false
        if (hypot(lastSX - holdAnchorX, lastSY - holdAnchorY) > 10f) {
            holdAnchorX = lastSX; holdAnchorY = lastSY
            scheduleHold()
        }
        invalidateLive()
    }

    private fun addLivePoint(sx: Float, sy: Float) {
        if (abs(sx - lastSX) < 1f && abs(sy - lastSY) < 1f) return
        lastSX = sx; lastSY = sy
        val pp = toPage(sx, sy)
        live.add(pp[0], pp[1])
        // Path 를 이어 붙이기만 한다 (buildPath 와 같은 곡선)
        val n = live.points
        if (n >= 3) {
            val x = live.x(n - 2); val y = live.y(n - 2)
            livePath.quadTo(x, y, (x + live.x(n - 1)) / 2f, (y + live.y(n - 1)) / 2f)
        }
    }

    /** 이보다 작은 획(일반 글씨 크기)은 부드러운 곡선 보정을 하지 않음 */
    private fun minCurvePage() = 160f / currentScale()

    private fun scheduleHold() {
        main.removeCallbacks(holdRunnable)
        if (prefs.holdToSnap) main.postDelayed(holdRunnable, 650)
    }

    /** 펜을 멈추고 있으면 도형으로 보정 */
    private fun onHold() {
        if (mode != Mode.DRAW || snapped || live.points < 4 || maxTravel < 20f) return
        val r = ShapeRecognizer.recognize(live.toArray(), minCurvePage()) ?: return
        live.set(r.pts)
        snapped = true; hasPrediction = false
        liveLabel = r.name
        invalidateLive()
        main.postDelayed({ if (liveLabel == r.name) { liveLabel = null; invalidateLive() } }, 900)
    }

    private fun onPointerUp(e: MotionEvent) {
        val id = e.getPointerId(e.actionIndex)
        if (mode == Mode.SWIPE) return   // 모든 손가락을 뗄 때까지 무시
        if (mode == Mode.GESTURE && (id == gId0 || id == gId1)) {
            endGesture()
            mode = Mode.IGNORE   // 남은 손가락으로 획이 그어지지 않도록
        } else if (id == activeId && mode != Mode.GESTURE && mode != Mode.IGNORE) {
            onUp(e, false)
            mode = Mode.IGNORE
        }
    }

    private fun endGesture() {
        // 두 손가락으로 톡 → 실행 취소
        val tap = SystemClock.uptimeMillis() - gTime < 280 && gMoved < 22f
        if (tap && prefs.twoFingerTapUndo) {
            viewMatrix.set(gStart)
            matrixChanged()
            if (canUndo) { undo(); liveLabel = "↶ 실행 취소" } else liveLabel = "취소할 것이 없습니다"
            main.postDelayed({ liveLabel = null; invalidateLive() }, 700)
            invalidateAll()
            return
        }
        clampPan()
        matrixChanged()
        inkDirty = true
        requestScreenRender()
    }

    private fun onUp(e: MotionEvent, cancel: Boolean) {
        main.removeCallbacks(holdRunnable)
        main.removeCallbacks(longPressRunnable)
        val p = currentPage ?: return
        hasPrediction = false
        when (mode) {
            Mode.DRAW -> {
                if (!cancel && live.points >= 1) {
                    // 필터 지연으로 끝이 짧아지지 않도록 실제 펜을 뗀 위치를 마지막 점으로
                    if (!snapped && prefs.smoothing > 0) {
                        val idx = e.findPointerIndex(activeId)
                        if (idx >= 0) addLivePoint(e.getX(idx), e.getY(idx))
                    }
                    var pts = live.toArray()
                    var shaped = snapped
                    if (!snapped && prefs.autoShape && pts.size >= 6) {
                        ShapeRecognizer.recognize(pts, minCurvePage())?.let { pts = it.pts; shaped = true }
                    }
                    if (pts.size == 2) pts = floatArrayOf(pts[0], pts[1], pts[0] + 0.01f, pts[1])
                    // 도형은 꼭짓점이 둥글어지지 않도록 촘촘히, 손글씨는 그대로 (그린 모양 그대로 저장)
                    if (shaped) pts = Geometry.densify(pts, 3f / currentScale())
                    commitStroke(p, Stroke(liveKind, liveColor, liveWidth, liveDashed, pts))
                }
                live.clear(); livePath.rewind()
            }
            Mode.ERASE -> endErase()
            Mode.GESTURE -> endGesture()
            else -> {}
        }
        if (liveLabel != null && !snapped && mode != Mode.GESTURE && mode != Mode.SWIPE) liveLabel = null
        if (snapped) main.postDelayed({ liveLabel = null; invalidateLive() }, 600)
        invalidateLive()
    }

    private fun commitStroke(p: Page, s: Stroke) {
        val before = p.strokes
        p.strokes = before + s
        pending += s
        pushOp(Op(p, before, p.strokes))
        invalidate()
    }

    fun cancelInput() {
        main.removeCallbacks(holdRunnable); main.removeCallbacks(longPressRunnable)
        live.clear(); livePath.rewind(); liveLabel = null; hasPrediction = false
        if (mode == Mode.ERASE) endErase()
        mode = Mode.IDLE
        eraserCursorR = 0f
        invalidateLive()
    }

    // ---------------- 지우개

    private fun startErase(sx: Float, sy: Float, r: Float, whole: Boolean) {
        mode = Mode.ERASE
        eraseBefore = currentPage?.strokes
        eraseWhole = whole
        eraserR = r
        lastEraseX = sx; lastEraseY = sy
        eraseAt(sx, sy)
        invalidate()
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
        if (cursor) { eraserCursorX = sx; eraserCursorY = sy; eraserCursorR = eraserR; invalidateLive() }
        val pp = toPage(sx, sy)
        val r = eraserR / currentScale()
        var changed = false
        val out = ArrayList<Stroke>(p.strokes.size + 4)
        for (s in p.strokes) {
            val res = Geometry.erase(s, pp[0], pp[1], r)
            when {
                res == null -> out += s
                eraseWhole -> changed = true              // 닿은 획 통째로 삭제
                else -> { out.addAll(res); changed = true }
            }
        }
        if (changed) {
            p.strokes = out
            inkDirty = true; pending.clear()
            invalidate()
        }
    }

    private fun endErase() {
        val p = currentPage
        val before = eraseBefore
        eraseBefore = null
        eraserCursorR = 0f
        invalidateLive()
        if (p != null && before != null && before !== p.strokes) pushOp(Op(p, before, p.strokes))
        invalidate()
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
