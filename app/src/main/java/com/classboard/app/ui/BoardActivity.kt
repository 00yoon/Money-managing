package com.classboard.app.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import com.classboard.app.model.BgColors
import com.classboard.app.model.NoteDocument
import com.classboard.app.model.Pattern
import com.classboard.app.storage.DocumentStore
import com.classboard.app.storage.PdfSource
import java.util.concurrent.Executors

class BoardActivity : Activity(), BoardView.Listener {

    companion object { const val EXTRA_KEY = "key" }

    private lateinit var store: DocumentStore
    private lateinit var board: BoardView
    private var doc: NoteDocument? = null
    private var pdf: PdfSource? = null
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private lateinit var rootLayout: FrameLayout
    private var quickMenu: View? = null
    private lateinit var barWrap: LinearLayout
    private lateinit var subPanel: LinearLayout
    private lateinit var subScroll: HorizontalScrollView
    private lateinit var toolButtons: Map<Tool, TextView>
    private val colorDots = ArrayList<View>()
    private lateinit var pageLabel: TextView
    private lateinit var undoBtn: TextView
    private lateinit var redoBtn: TextView
    private lateinit var rulerBtn: TextView
    private lateinit var protBtn: TextView
    private lateinit var compassBtn: TextView

    private val quickColors = intArrayOf(Color.BLACK, Color.rgb(224, 49, 49), Color.rgb(25, 113, 194), Color.rgb(47, 158, 68), Color.rgb(247, 103, 7), Color.WHITE)
    private val moreColors = intArrayOf(
        Color.BLACK, Color.rgb(73, 80, 87), Color.WHITE, Color.rgb(224, 49, 49), Color.rgb(230, 73, 128), Color.rgb(156, 54, 181),
        Color.rgb(103, 65, 217), Color.rgb(25, 113, 194), Color.rgb(16, 152, 173), Color.rgb(47, 158, 68), Color.rgb(116, 184, 22),
        Color.rgb(250, 176, 5), Color.rgb(247, 103, 7), Color.rgb(132, 94, 60),
    )
    private val hlColors = intArrayOf(Color.rgb(255, 212, 59), Color.rgb(140, 233, 154), Color.rgb(250, 162, 193), Color.rgb(116, 192, 252), Color.rgb(255, 169, 77))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        store = DocumentStore(this)

        val root = FrameLayout(this)
        board = BoardView(this)
        board.listener = this
        root.addView(board, FrameLayout.LayoutParams(-1, -1))
        root.addView(board.overlay, FrameLayout.LayoutParams(-1, -1))
        rootLayout = root
        buildToolbar(root)
        setContentView(root)
        hideSystemUi()

        val key = intent.getStringExtra(EXTRA_KEY) ?: run { finish(); return }
        io.execute {
            try {
                val d = store.load(key)
                val src = if (d.hasPdf) PdfSource(store.pdfFile(key)) else null
                main.post {
                    if (isDestroyed) { src?.close(); return@post }
                    doc = d; pdf = src
                    board.setDocument(d, src)
                }
            } catch (e: Exception) {
                main.post { Toast.makeText(this, "파일을 열 수 없습니다: ${e.message}", Toast.LENGTH_LONG).show(); finish() }
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    @Suppress("DEPRECATION")
    private fun hideSystemUi() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
    }

    // ================================================================ 툴바

    private fun buildToolbar(root: FrameLayout) {
        barWrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        subPanel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            val p = Ui.dpi(context, 6f); setPadding(p, p, p, p)
        }
        subScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            background = Ui.rounded(Ui.BAR_BG, Ui.dp(context, 16f), Ui.dpi(context, 1f), 0xFFCED4DA.toInt())
            elevation = Ui.dp(context, 6f)
            addView(subPanel)
            visibility = View.GONE
        }
        barWrap.addView(subScroll, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = Ui.dpi(this@BoardActivity, 8f) })

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            val p = Ui.dpi(context, 6f); setPadding(p, p, p, p)
        }
        val barScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            background = Ui.rounded(Ui.BAR_BG, Ui.dp(context, 18f), Ui.dpi(context, 1f), 0xFFCED4DA.toInt())
            elevation = Ui.dp(context, 8f)
            addView(bar)
        }
        barWrap.addView(barScroll, LinearLayout.LayoutParams(-2, -2))

        bar.addView(Ui.button(this, "⌂ 홈") { finish() })
        bar.addView(Ui.divider(this))

        val pen = Ui.button(this, "✎ 펜") { selectTool(Tool.PEN) }
        val hl = Ui.button(this, "▍형광펜") { selectTool(Tool.HIGHLIGHTER) }
        val er = Ui.button(this, "⌫ 지우개") { selectTool(Tool.ERASER) }
        val sh = Ui.button(this, "△ 도형") { selectTool(Tool.SHAPE) }
        toolButtons = mapOf(Tool.PEN to pen, Tool.HIGHLIGHTER to hl, Tool.ERASER to er, Tool.SHAPE to sh)
        toolButtons.values.forEach { bar.addView(it) }
        bar.addView(Ui.divider(this))

        for (c in quickColors) {
            val dot = Ui.colorDot(this, c) { v -> applyColor(v.tag as Int) }
            colorDots += dot; bar.addView(dot)
        }
        bar.addView(Ui.divider(this))

        rulerBtn = Ui.button(this, "📏 자") { Ui.setSelected(rulerBtn, board.toggleTool(board.ruler)) }
        protBtn = Ui.button(this, "◠ 각도기") { Ui.setSelected(protBtn, board.toggleTool(board.protractor)) }
        compassBtn = Ui.button(this, "⊙ 컴퍼스") { Ui.setSelected(compassBtn, board.toggleTool(board.compass)) }
        bar.addView(rulerBtn); bar.addView(protBtn); bar.addView(compassBtn)
        bar.addView(Ui.divider(this))

        undoBtn = Ui.button(this, "↶", 24f) { board.undo() }
        redoBtn = Ui.button(this, "↷", 24f) { board.redo() }
        bar.addView(undoBtn); bar.addView(redoBtn)
        bar.addView(Ui.divider(this))

        bar.addView(Ui.button(this, "◀", 20f) { board.goToPage(board.pageIndex - 1) })
        pageLabel = Ui.button(this, "- / -") { showPageGrid() }
        bar.addView(pageLabel)
        bar.addView(Ui.button(this, "▶", 20f) { board.goToPage(board.pageIndex + 1) })
        bar.addView(Ui.button(this, "＋쪽") { showBlankPageDialog(insert = true) })
        bar.addView(Ui.divider(this))

        bar.addView(Ui.button(this, "－", 22f) { board.zoomBy(1f / 1.25f) })
        bar.addView(Ui.button(this, "맞춤") { board.fitPage() })
        bar.addView(Ui.button(this, "＋", 22f) { board.zoomBy(1.25f) })
        bar.addView(Ui.divider(this))
        val more = Ui.button(this, "⋯", 24f) {}
        more.setOnClickListener { showMenu(it) }
        bar.addView(more)

        root.addView(barWrap, FrameLayout.LayoutParams(-2, -2).apply {
            gravity = Gravity.BOTTOM or toolbarGravity()
            val m = Ui.dpi(this@BoardActivity, 12f); setMargins(m, m, m, m)
        })
        barWrap.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            board.bottomInset = barScroll.height + Ui.dpi(this, 20f)
        }

        selectTool(Tool.PEN)
    }

    private fun toolbarGravity() = when (board.prefs.toolbarPos) { 0 -> Gravity.START; 2 -> Gravity.END; else -> Gravity.CENTER_HORIZONTAL }

    private fun setToolbarPos(pos: Int) {
        board.prefs.toolbarPos = pos
        val lp = barWrap.layoutParams as FrameLayout.LayoutParams
        lp.gravity = Gravity.BOTTOM or toolbarGravity()
        barWrap.layoutParams = lp
        (subScroll.layoutParams as LinearLayout.LayoutParams).gravity = toolbarGravity()
        subScroll.requestLayout()
    }

    private fun selectTool(t: Tool) {
        val again = board.tool == t && subScroll.visibility == View.GONE
        val wasSame = board.tool == t
        board.tool = t
        toolButtons.forEach { (k, v) -> Ui.setSelected(v, k == t) }
        refreshColorDots()
        // 같은 도구를 한 번 더 누르면 세부 설정 패널 열기/닫기. 도형은 항상 종류 선택 표시.
        when {
            t == Tool.SHAPE -> showSubPanel(t)
            again -> showSubPanel(t)
            wasSame -> subScroll.visibility = View.GONE
            else -> subScroll.visibility = View.GONE
        }
    }

    private fun applyColor(c: Int) {
        if (board.tool == Tool.HIGHLIGHTER) { board.hlColor = c; board.prefs.hlColor = c }
        else {
            board.penColor = c; board.prefs.penColor = c
            if (board.tool == Tool.ERASER) selectTool(Tool.PEN)
        }
        refreshColorDots()
        if (subScroll.visibility == View.VISIBLE) showSubPanel(board.tool)
    }

    private fun refreshColorDots() {
        val cur = if (board.tool == Tool.HIGHLIGHTER) board.hlColor else board.penColor
        colorDots.forEach { Ui.setDot(it, it.tag == cur && board.tool != Tool.ERASER) }
    }

    private fun showSubPanel(t: Tool) {
        subPanel.removeAllViews()
        when (t) {
            Tool.PEN, Tool.SHAPE -> {
                if (t == Tool.SHAPE) {
                    val btns = ArrayList<TextView>()
                    for (st in ShapeType.values()) {
                        val b = Ui.button(this, st.label) { board.shapeType = st; btns.forEach { Ui.setSelected(it, it.tag == st) } }
                        b.tag = st; Ui.setSelected(b, board.shapeType == st)
                        btns += b; subPanel.addView(b)
                    }
                    subPanel.addView(Ui.divider(this))
                }
                addWidthButtons(floatArrayOf(2f, 4f, 7f, 12f, 20f), board.penWidthPx) { board.penWidthPx = it; board.prefs.penWidth = it }
                subPanel.addView(Ui.divider(this))
                val dash = Ui.button(this, "┅ 점선") {}
                Ui.setSelected(dash, board.dashed)
                dash.setOnClickListener { board.dashed = !board.dashed; Ui.setSelected(dash, board.dashed) }
                subPanel.addView(dash)
                if (t == Tool.PEN) {
                    subPanel.addView(Ui.divider(this))
                    for (c in moreColors) {
                        val d = Ui.colorDot(this, c) { v -> applyColor(v.tag as Int) }
                        Ui.setDot(d, c == board.penColor); subPanel.addView(d)
                    }
                }
            }
            Tool.HIGHLIGHTER -> {
                addWidthButtons(floatArrayOf(14f, 24f, 40f), board.hlWidthPx) { board.hlWidthPx = it; board.prefs.hlWidth = it }
                subPanel.addView(Ui.divider(this))
                for (c in hlColors) {
                    val d = Ui.colorDot(this, c) { v -> applyColor(v.tag as Int) }
                    Ui.setDot(d, c == board.hlColor); subPanel.addView(d)
                }
            }
            Tool.ERASER -> {
                val partial = Ui.button(this, "부분 지우기") {}
                val whole = Ui.button(this, "획 통째로") {}
                fun refresh() { Ui.setSelected(partial, !board.prefs.strokeEraser); Ui.setSelected(whole, board.prefs.strokeEraser) }
                partial.setOnClickListener { board.prefs.strokeEraser = false; refresh() }
                whole.setOnClickListener { board.prefs.strokeEraser = true; refresh() }
                refresh()
                subPanel.addView(partial); subPanel.addView(whole)
                subPanel.addView(Ui.divider(this))
                val sizes = listOf("작게" to 15f, "보통" to 30f, "크게" to 60f, "아주 크게" to 110f)
                val btns = ArrayList<TextView>()
                for ((label, r) in sizes) {
                    val b = Ui.button(this, label) { board.eraserRadiusPx = r; board.prefs.eraserRadius = r; btns.forEach { Ui.setSelected(it, it.tag == r) } }
                    b.tag = r; Ui.setSelected(b, board.eraserRadiusPx == r); btns += b; subPanel.addView(b)
                }
                subPanel.addView(Ui.divider(this))
                subPanel.addView(Ui.button(this, "🗑 이 페이지 전체 지우기") { board.clearPage(); subScroll.visibility = View.GONE })
            }
        }
        subPanel.addView(Ui.divider(this))
        subPanel.addView(Ui.button(this, "✕") { subScroll.visibility = View.GONE })
        subScroll.visibility = View.VISIBLE
    }

    private fun addWidthButtons(widths: FloatArray, current: Float, set: (Float) -> Unit) {
        val views = ArrayList<View>()
        for (w in widths) {
            val v = object : View(this) {
                val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(33, 37, 41) }
                override fun onDraw(c: android.graphics.Canvas) {
                    val r = (w / 2f).coerceAtLeast(1.5f).coerceAtMost(height * 0.4f)
                    c.drawCircle(width / 2f, height / 2f, r, paint)
                }
            }
            v.tag = w
            v.layoutParams = LinearLayout.LayoutParams(Ui.dpi(this, 56f), Ui.dpi(this, 56f)).apply { val m = Ui.dpi(this@BoardActivity, 2f); setMargins(m, m, m, m) }
            v.background = if (w == current) Ui.rounded(0x331E5BB8, Ui.dp(this, 10f)) else Ui.pressable(this, Color.TRANSPARENT)
            v.setOnClickListener {
                set(w)
                views.forEach { o -> o.background = if (o.tag == w) Ui.rounded(0x331E5BB8, Ui.dp(this, 10f)) else Ui.pressable(this, Color.TRANSPARENT) }
            }
            views += v; subPanel.addView(v)
        }
    }

    private fun showMenu(anchor: View) {
        val pm = PopupMenu(this, anchor)
        val m = pm.menu
        val page = board.currentPage
        m.add(0, 1, 0, "빈 페이지 추가")
        if (page?.isBlank == true) {
            m.add(0, 2, 0, "페이지 배경 바꾸기")
            m.add(0, 3, 0, "이 빈 페이지 삭제")
        }
        m.add(0, 4, 0, if (board.fitWidth) "화면 맞춤: 쪽 전체로" else "화면 맞춤: 폭에 맞추기")
        m.add(0, 5, 0, "모든 획 자동 도형 보정").apply { isCheckable = true; isChecked = board.prefs.autoShape }
        m.add(0, 6, 0, "멈추면 도형 보정").apply { isCheckable = true; isChecked = board.prefs.holdToSnap }
        m.add(0, 7, 0, "툴바 위치: 왼쪽")
        m.add(0, 8, 0, "툴바 위치: 가운데")
        m.add(0, 9, 0, "툴바 위치: 오른쪽")
        m.add(0, 10, 0, "설정 (손바닥 지우개 등)")
        pm.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> showBlankPageDialog(insert = true)
                2 -> showBlankPageDialog(insert = false)
                3 -> AlertDialog.Builder(this).setMessage("이 빈 페이지와 필기를 삭제할까요?")
                    .setPositiveButton("삭제") { _, _ -> board.deleteCurrentBlankPage() }
                    .setNegativeButton("취소", null).show()
                4 -> board.setFitWidth(!board.fitWidth)
                5 -> board.prefs.autoShape = !board.prefs.autoShape
                6 -> board.prefs.holdToSnap = !board.prefs.holdToSnap
                7 -> setToolbarPos(0)
                8 -> setToolbarPos(1)
                9 -> setToolbarPos(2)
                10 -> startActivity(Intent(this, SettingsActivity::class.java))
            }
            true
        }
        pm.show()
    }

    /** 빈 페이지 배경 고르기 (추가 또는 현재 빈 페이지 변경) */
    private fun showBlankPageDialog(insert: Boolean) {
        var pattern = board.currentPage?.takeIf { it.isBlank }?.pattern ?: Pattern.NONE
        var bg = board.currentPage?.takeIf { it.isBlank }?.bgColor ?: BgColors.WHITE
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; val p = Ui.dpi(context, 16f); setPadding(p, p, p, p) }
        fun row(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }.also { box.addView(it) }
        box.addView(TextView(this).apply { text = "무늬"; textSize = 16f })
        val r1 = row()
        val pBtns = ArrayList<TextView>()
        for (p in Pattern.values()) {
            val b = Ui.button(this, p.label) { pattern = p; pBtns.forEach { Ui.setSelected(it, it.tag == p) } }
            b.tag = p; Ui.setSelected(b, p == pattern); pBtns += b; r1.addView(b)
        }
        box.addView(TextView(this).apply { text = "배경색"; textSize = 16f; setPadding(0, Ui.dpi(context, 12f), 0, 0) })
        val r2 = row()
        val cBtns = ArrayList<TextView>()
        for ((c, label) in BgColors.all) {
            val b = Ui.button(this, label) { bg = c; cBtns.forEach { Ui.setSelected(it, it.tag == c) } }
            b.tag = c; Ui.setSelected(b, c == bg); cBtns += b; r2.addView(b)
        }
        AlertDialog.Builder(this)
            .setTitle(if (insert) "빈 페이지 추가 (현재 쪽 뒤)" else "페이지 배경")
            .setView(box)
            .setPositiveButton(if (insert) "추가" else "적용") { _, _ ->
                if (insert) board.insertBlankPage(pattern, bg) else board.setPageBackground(pattern, bg)
                // 어두운 배경이면 펜을 흰색으로
                if (Color.luminance(bg) < 0.4f && Color.luminance(board.penColor) < 0.4f) applyColor(Color.WHITE)
                else if (Color.luminance(bg) >= 0.4f && board.penColor == Color.WHITE) applyColor(Color.BLACK)
            }
            .setNegativeButton("취소", null)
            .show()
    }

    // ================================================================ 페이지 목록

    private fun showPageGrid() {
        val count = board.pageCount
        if (count == 0) return
        val thumbIo = Executors.newFixedThreadPool(1)
        val cache = HashMap<Int, Bitmap>()
        val thumbW = Ui.dpi(this, 180f)
        lateinit var dialog: AlertDialog
        val grid = GridView(this).apply {
            columnWidth = thumbW + Ui.dpi(context, 16f)
            numColumns = GridView.AUTO_FIT
            stretchMode = GridView.STRETCH_SPACING
            verticalSpacing = Ui.dpi(context, 12f)
            val p = Ui.dpi(context, 12f); setPadding(p, p, p, p)
        }
        val adapter = object : BaseAdapter() {
            override fun getCount() = count
            override fun getItem(position: Int) = position
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val cell = (convertView as? LinearLayout) ?: LinearLayout(this@BoardActivity).apply {
                    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                    addView(ImageView(context).apply {
                        layoutParams = LinearLayout.LayoutParams(thumbW, (thumbW * 0.75f).toInt())
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        setBackgroundColor(0xFFE9ECEF.toInt())
                    })
                    addView(TextView(context).apply { gravity = Gravity.CENTER; textSize = 15f })
                }
                val img = cell.getChildAt(0) as ImageView
                val label = cell.getChildAt(1) as TextView
                label.text = "${position + 1}  ·  ${board.pageLabel(position)}"
                label.setTextColor(if (position == board.pageIndex) Ui.SELECTED else Ui.TEXT)
                img.tag = position
                val b = cache[position]
                if (b != null) img.setImageBitmap(b) else {
                    img.setImageBitmap(null)
                    thumbIo.execute {
                        val bmp = runCatching { board.renderThumbnail(position, thumbW) }.getOrNull() ?: return@execute
                        main.post { cache[position] = bmp; if (img.tag == position) img.setImageBitmap(bmp) }
                    }
                }
                return cell
            }
        }
        grid.adapter = adapter
        grid.setOnItemClickListener { _, _, pos, _ -> board.goToPage(pos); dialog.dismiss() }
        dialog = AlertDialog.Builder(this).setTitle("페이지 목록").setView(grid).setNegativeButton("닫기", null).create()
        dialog.setOnDismissListener { thumbIo.shutdownNow(); main.postDelayed({ cache.values.forEach { it.recycle() } }, 500) }
        dialog.show()
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.85f).toInt(), (resources.displayMetrics.heightPixels * 0.85f).toInt())
        grid.post { grid.setSelection(board.pageIndex) }
    }

    // ================================================================ BoardView.Listener

    override fun onPageChanged(index: Int, count: Int) {
        pageLabel.text = "${index + 1} / $count"
        scheduleSave()
    }

    override fun onHistoryChanged(canUndo: Boolean, canRedo: Boolean) {
        undoBtn.alpha = if (canUndo) 1f else 0.35f
        redoBtn.alpha = if (canRedo) 1f else 0.35f
    }

    override fun onDocChanged() = scheduleSave()

    override fun onMessage(msg: String) { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }

    // ================================================================ 빠른 메뉴 (펜을 길게 누른 자리에)

    private val quickMenuColors = intArrayOf(
        Color.BLACK, Color.rgb(224, 49, 49), Color.rgb(25, 113, 194), Color.rgb(47, 158, 68),
        Color.rgb(247, 103, 7), Color.rgb(156, 54, 181), Color.WHITE,
    )

    override fun onQuickMenu(x: Float, y: Float) {
        dismissQuickMenu()
        val catcher = FrameLayout(this).apply {
            setBackgroundColor(0x14000000)
            isClickable = true
            setOnClickListener { dismissQuickMenu() }
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = Ui.dpi(context, 10f); setPadding(p, p, p, p)
            background = Ui.rounded(0xF8FFFFFF.toInt(), Ui.dp(context, 22f), Ui.dpi(context, 1f), 0xFFADB5BD.toInt())
            elevation = Ui.dp(context, 12f)
            isClickable = true   // 패널 안 빈 곳을 눌러도 닫히지 않게
        }
        // 1줄: 색 (누르면 바로 펜 + 그 색)
        val colors = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val curColor = if (board.tool == Tool.HIGHLIGHTER) board.hlColor else board.penColor
        val dotColors = if (board.tool == Tool.HIGHLIGHTER) hlColors else quickMenuColors
        for (c in dotColors) {
            val d = Ui.colorDot(this, c) { v ->
                if (board.tool != Tool.HIGHLIGHTER && board.tool != Tool.SHAPE) selectTool(Tool.PEN)
                applyColor(v.tag as Int); dismissQuickMenu()
            }
            val sz = Ui.dpi(this, 60f)
            d.layoutParams = LinearLayout.LayoutParams(sz, sz).apply { val m = Ui.dpi(this@BoardActivity, 6f); setMargins(m, m, m, m) }
            Ui.setDot(d, c == curColor && board.tool != Tool.ERASER)
            colors.addView(d)
        }
        panel.addView(colors)
        // 2줄: 도구
        val tools = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        fun tb(label: String, sel: Boolean, act: () -> Unit) = Ui.button(this, label, 18f) { act(); dismissQuickMenu() }.also { Ui.setSelected(it, sel); tools.addView(it) }
        tb("✎ 펜", board.tool == Tool.PEN) { selectTool(Tool.PEN) }
        tb("▍형광펜", board.tool == Tool.HIGHLIGHTER) { selectTool(Tool.HIGHLIGHTER) }
        tb("⌫ 지우개", board.tool == Tool.ERASER) { selectTool(Tool.ERASER) }
        tb("↶ 취소", false) { board.undo() }
        tb("🗑 전체 지우기", false) { board.clearPage() }
        panel.addView(tools)
        // 3줄: 굵기
        if (board.tool == Tool.PEN || board.tool == Tool.SHAPE) {
            val widths = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
            for ((label, w) in listOf("가늘게" to 2.5f, "보통" to 4f, "굵게" to 7f, "아주 굵게" to 12f)) {
                val b = Ui.button(this, label, 16f) { board.penWidthPx = w; board.prefs.penWidth = w; dismissQuickMenu() }
                Ui.setSelected(b, board.penWidthPx == w); widths.addView(b)
            }
            panel.addView(widths)
        }
        catcher.addView(panel, FrameLayout.LayoutParams(-2, -2))
        rootLayout.addView(catcher, FrameLayout.LayoutParams(-1, -1))
        quickMenu = catcher
        // 크기를 안 뒤 누른 자리 위쪽(공간이 없으면 아래쪽)에 배치
        panel.visibility = View.INVISIBLE
        panel.post {
            val gap = Ui.dp(this, 36f)
            val w = panel.width.toFloat(); val h = panel.height.toFloat()
            val maxX = rootLayout.width - w - 8f; val maxY = rootLayout.height - h - 8f
            panel.x = (x - w / 2f).coerceIn(8f, maxX.coerceAtLeast(8f))
            panel.y = (if (y - h - gap > 8f) y - h - gap else y + gap).coerceIn(8f, maxY.coerceAtLeast(8f))
            panel.visibility = View.VISIBLE
        }
    }

    private fun dismissQuickMenu() {
        quickMenu?.let { rootLayout.removeView(it) }
        quickMenu = null
    }

    override fun onBackPressed() {
        if (quickMenu != null) { dismissQuickMenu(); return }
        @Suppress("DEPRECATION") super.onBackPressed()
    }

    // ================================================================ 자동 저장

    private val saveRunnable = Runnable { saveNow() }

    private fun scheduleSave() {
        main.removeCallbacks(saveRunnable)
        main.postDelayed(saveRunnable, 1500)
    }

    private fun saveNow() {
        main.removeCallbacks(saveRunnable)
        val d = doc ?: return
        d.lastPage = board.pageIndex
        val json = d.toJson().toString()
        val key = d.key; val title = d.title; val n = d.pages.size; val hasPdf = d.hasPdf
        io.execute { runCatching { store.saveJson(key, title, n, hasPdf, json) } }
    }

    override fun onPause() {
        super.onPause()
        board.cancelInput()
        saveNow()
    }

    override fun onResume() {
        super.onResume()
        // 설정 화면에서 돌아왔을 때 반영되도록 Prefs 는 매번 읽음 (BoardView.prefs 가 SharedPreferences 를 직접 읽는다)
        hideSystemUi()
    }

    override fun onDestroy() {
        super.onDestroy()
        io.execute { pdf?.close() }
        io.shutdown()
    }
}
