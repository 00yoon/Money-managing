package com.classboard.app.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.classboard.app.storage.DocMeta
import com.classboard.app.storage.DocumentStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** 첫 화면: PDF 열기, 빈 칠판, 최근 자료 목록 */
class MainActivity : Activity() {

    private val REQ_OPEN = 1
    private val REQ_PERM = 2

    private lateinit var store: DocumentStore
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var items: List<DocMeta> = emptyList()
    private lateinit var adapter: BaseAdapter
    private lateinit var emptyText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = DocumentStore(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = Ui.dpi(context, 32f); setPadding(p, p, p, p)
        }
        root.addView(TextView(this).apply {
            text = "수업판서"; textSize = 36f; setTextColor(Ui.TEXT)
            paint.isFakeBoldText = true
        })
        root.addView(TextView(this).apply {
            text = "PDF 위에 판서하고, 필기는 자동으로 저장됩니다."; textSize = 18f; setTextColor(0xFF6C757D.toInt())
            setPadding(0, Ui.dpi(context, 4f), 0, Ui.dpi(context, 20f))
        })

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(bigButton("📂  PDF 열기", "USB · 구글 드라이브 · 내장 메모리") { openPicker() })
        actions.addView(bigButton("🗂  기기에서 찾기", "파일 선택 창이 안 될 때") { browseFiles() })
        actions.addView(bigButton("＋  빈 칠판", "PDF 없이 바로 판서") { openBlank() })
        actions.addView(bigButton("⚙  설정", "손바닥 지우개 · 터치 보정") { startActivity(Intent(this, SettingsActivity::class.java)) })
        root.addView(actions)

        root.addView(TextView(this).apply {
            text = "최근 자료  (길게 누르면 이름 변경 · 삭제)"; textSize = 20f; setTextColor(Ui.TEXT)
            setPadding(0, Ui.dpi(context, 28f), 0, Ui.dpi(context, 8f))
        })
        emptyText = TextView(this).apply { text = "아직 연 자료가 없습니다."; textSize = 17f; setTextColor(0xFF868E96.toInt()) }
        root.addView(emptyText)

        val list = ListView(this)
        adapter = object : BaseAdapter() {
            override fun getCount() = items.size
            override fun getItem(position: Int) = items[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val cell = (convertView as? LinearLayout) ?: LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    val p = Ui.dpi(context, 14f); setPadding(p, p, p, p)
                    addView(TextView(context).apply { textSize = 20f; setTextColor(Ui.TEXT) })
                    addView(TextView(context).apply { textSize = 15f; setTextColor(0xFF868E96.toInt()) })
                }
                val m = items[position]
                (cell.getChildAt(0) as TextView).text = (if (m.hasPdf) "📄  " else "🟩  ") + m.title
                val df = SimpleDateFormat("M월 d일 HH:mm", Locale.KOREA)
                (cell.getChildAt(1) as TextView).text = "${if (m.hasPdf) "PDF" else "빈 칠판"} · ${m.pageCount}쪽 · ${df.format(Date(m.updated))}"
                return cell
            }
        }
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ -> openBoard(items[pos].key) }
        list.setOnItemLongClickListener { _, _, pos, _ -> itemMenu(items[pos]); true }
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        handleViewIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleViewIntent(intent)
    }

    private fun handleViewIntent(i: Intent?) {
        if (i?.action == Intent.ACTION_VIEW) i.data?.let { importAndOpen(it) }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        io.execute {
            val l = store.list()
            main.post { items = l; adapter.notifyDataSetChanged(); emptyText.visibility = if (l.isEmpty()) View.VISIBLE else View.GONE }
        }
    }

    private fun bigButton(title: String, sub: String, onClick: () -> Unit): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        val p = Ui.dpi(context, 18f); setPadding(p, p, p, p)
        background = Ui.pressable(context, 0xFFFFFFFF.toInt())
        elevation = Ui.dp(context, 3f)
        addView(TextView(context).apply { text = title; textSize = 22f; setTextColor(Ui.TEXT); gravity = Gravity.CENTER })
        addView(TextView(context).apply { text = sub; textSize = 14f; setTextColor(0xFF868E96.toInt()); gravity = Gravity.CENTER })
        layoutParams = LinearLayout.LayoutParams(0, Ui.dpi(context, 120f), 1f).apply { val m = Ui.dpi(context, 8f); setMargins(m, m, m, m) }
        setOnClickListener { onClick() }
    }

    // ---------------------------------------------------------------- 열기

    private fun openPicker() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/pdf"
            putExtra("android.content.extra.SHOW_ADVANCED", true) // 내장 메모리 표시
        }
        try { startActivityForResult(i, REQ_OPEN) } catch (e: ActivityNotFoundException) {
            try {
                startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).apply { type = "application/pdf"; addCategory(Intent.CATEGORY_OPENABLE) }, REQ_OPEN)
            } catch (e2: ActivityNotFoundException) { browseFiles() }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_OPEN && resultCode == RESULT_OK) data?.data?.let { importAndOpen(it) }
    }

    private fun importAndOpen(uri: Uri) {
        val dlg = AlertDialog.Builder(this).setView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            val p = Ui.dpi(context, 24f); setPadding(p, p, p, p)
            addView(ProgressBar(context))
            addView(TextView(context).apply { text = "  PDF 불러오는 중…"; textSize = 18f })
        }).setCancelable(false).show()
        io.execute {
            val r = runCatching { store.importPdf(uri) }
            main.post {
                dlg.dismiss()
                r.onSuccess { openBoard(it) }.onFailure { Toast.makeText(this, "열 수 없는 파일입니다: ${it.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun openBlank() {
        io.execute {
            val key = store.createBlank()
            main.post { openBoard(key) }
        }
    }

    private fun openBoard(key: String) {
        startActivity(Intent(this, BoardActivity::class.java).putExtra(BoardActivity.EXTRA_KEY, key))
    }

    private fun itemMenu(m: DocMeta) {
        AlertDialog.Builder(this).setTitle(m.title)
            .setItems(arrayOf("이름 바꾸기", "삭제")) { _, which ->
                if (which == 0) {
                    val et = EditText(this).apply { setText(m.title); textSize = 20f }
                    AlertDialog.Builder(this).setTitle("이름 바꾸기").setView(et)
                        .setPositiveButton("확인") { _, _ -> io.execute { store.rename(m.key, et.text.toString().ifBlank { m.title }); main.post { refresh() } } }
                        .setNegativeButton("취소", null).show()
                } else {
                    AlertDialog.Builder(this).setMessage("'${m.title}' 과(와) 필기를 모두 삭제할까요?")
                        .setPositiveButton("삭제") { _, _ -> io.execute { store.delete(m.key); main.post { refresh() } } }
                        .setNegativeButton("취소", null).show()
                }
            }.show()
    }

    // ---------------------------------------------------------------- 간단한 파일 탐색기 (USB / 내장 메모리)

    private fun browseFiles() {
        if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), REQ_PERM)
            return
        }
        showDir(null)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERM) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) showDir(null)
            else Toast.makeText(this, "저장소 권한이 필요합니다", Toast.LENGTH_LONG).show()
        }
    }

    private fun storageRoots(): List<Pair<String, File>> {
        val out = ArrayList<Pair<String, File>>()
        @Suppress("DEPRECATION")
        val internal = Environment.getExternalStorageDirectory()
        out += "내장 메모리" to internal
        val seen = HashSet<String>().apply { add(internal.canonicalPath) }
        val candidates = ArrayList<File>()
        File("/storage").listFiles()?.let { candidates.addAll(it) }
        listOf("/mnt/usb", "/mnt/usb_storage", "/mnt/usbhost", "/mnt/media_rw", "/mnt/external_sd", "/mnt/sdcard/usb").forEach { p ->
            val f = File(p); if (f.isDirectory) { candidates += f; f.listFiles()?.let { candidates.addAll(it) } }
        }
        for (f in candidates) {
            if (f.name == "self" || f.name == "emulated" || !f.isDirectory || !f.canRead()) continue
            val cp = runCatching { f.canonicalPath }.getOrDefault(f.path)
            if (seen.add(cp)) out += "USB / 외부 저장소 (${f.name})" to f
        }
        return out
    }

    private fun showDir(dir: File?) {
        val entries = ArrayList<Pair<String, File?>>()
        val title: String
        if (dir == null) {
            title = "저장소 선택"
            storageRoots().forEach { entries += ("💾  " + it.first) to it.second }
        } else {
            title = dir.path
            entries += "⬆  상위 폴더" to (dir.parentFile?.takeIf { storageRoots().none { r -> r.second.path == dir.path } })
            val files = dir.listFiles().orEmpty().filter { !it.name.startsWith(".") }
            files.filter { it.isDirectory }.sortedBy { it.name.lowercase() }.forEach { entries += ("📁  " + it.name) to it }
            files.filter { it.isFile && it.name.endsWith(".pdf", true) }.sortedBy { it.name.lowercase() }.forEach { entries += ("📄  " + it.name) to it }
        }
        AlertDialog.Builder(this).setTitle(title)
            .setItems(entries.map { it.first }.toTypedArray()) { _, which ->
                val f = entries[which].second
                when {
                    f == null -> showDir(null)
                    f.isDirectory -> showDir(f)
                    else -> importAndOpen(Uri.fromFile(f))
                }
            }
            .setNegativeButton("닫기", null).show()
    }
}
