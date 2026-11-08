package com.classboard.app.storage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.classboard.app.model.NoteDocument
import com.classboard.app.model.Page
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** 최근 문서 목록에 표시할 요약 정보 */
data class DocMeta(val key: String, val title: String, val updated: Long, val pageCount: Int, val hasPdf: Boolean)

/**
 * 모든 자료를 앱 내부 저장소에 보관한다.
 *  notes/<key>.pdf       : 원본 PDF 복사본 (USB 를 빼도 계속 열 수 있도록)
 *  notes/<key>.json      : 페이지 구성 + 필기
 *  notes/<key>.meta.json : 목록용 요약
 */
class DocumentStore(context: Context) {
    private val root = File(context.filesDir, "notes").apply { mkdirs() }
    private val resolver = context.contentResolver

    fun pdfFile(key: String) = File(root, "$key.pdf")
    private fun dataFile(key: String) = File(root, "$key.json")
    private fun metaFile(key: String) = File(root, "$key.meta.json")

    /** content:// 또는 file:// URI 의 PDF 를 가져온다. 같은 파일(이름+크기)이면 기존 필기를 이어서 쓴다. */
    fun importPdf(uri: Uri): String {
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "문서.pdf"
        var size = -1L
        if (uri.scheme == "content") {
            runCatching {
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        c.getString(0)?.let { name = it }
                        if (!c.isNull(1)) size = c.getLong(1)
                    }
                }
            }
        } else if (uri.scheme == "file") {
            uri.path?.let { size = File(it).length() }
        }
        val key = sha1("$name|$size").take(16)
        val dest = pdfFile(key)
        if (!dest.exists() || (size > 0 && dest.length() != size)) {
            val tmp = File(root, "$key.pdf.tmp")
            resolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "파일을 열 수 없습니다" }
                tmp.outputStream().use { input.copyTo(it, 1 shl 16) }
            }
            tmp.renameTo(dest)
        }
        if (!dataFile(key).exists()) {
            val doc = createFromPdf(key, name.removeSuffix(".pdf").removeSuffix(".PDF"))
            save(doc)
        }
        return key
    }

    private fun createFromPdf(key: String, title: String): NoteDocument {
        val pages = mutableListOf<Page>()
        ParcelFileDescriptor.open(pdfFile(key), ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { r ->
                for (i in 0 until r.pageCount) {
                    r.openPage(i).use { p -> pages += Page(i, p.width.toFloat(), p.height.toFloat()) }
                }
            }
        }
        return NoteDocument(key, title, true, pages)
    }

    fun createBlank(): String {
        val key = "b" + UUID.randomUUID().toString().replace("-", "").take(15)
        val df = java.text.SimpleDateFormat("M월 d일 HH:mm", java.util.Locale.KOREA)
        val doc = NoteDocument(key, "빈 칠판 " + df.format(java.util.Date()), false, mutableListOf(Page.blank()))
        save(doc)
        return key
    }

    fun load(key: String): NoteDocument = NoteDocument.fromJson(JSONObject(dataFile(key).readText()))

    /** 원자적 저장 (쓰는 중에 전원이 꺼져도 이전 파일이 남도록) */
    @Synchronized
    fun save(doc: NoteDocument) {
        writeAtomic(dataFile(doc.key), doc.toJson().toString())
        val meta = JSONObject().apply {
            put("key", doc.key); put("title", doc.title)
            put("updated", System.currentTimeMillis())
            put("pages", doc.pages.size); put("hasPdf", doc.hasPdf)
        }
        writeAtomic(metaFile(doc.key), meta.toString())
    }

    /** 저장 직전 JSON 문자열 만들기는 메인스레드에서, 쓰기는 백그라운드에서 하기 위해 분리 */
    @Synchronized
    fun saveJson(key: String, title: String, pages: Int, hasPdf: Boolean, json: String) {
        writeAtomic(dataFile(key), json)
        val meta = JSONObject().apply {
            put("key", key); put("title", title)
            put("updated", System.currentTimeMillis())
            put("pages", pages); put("hasPdf", hasPdf)
        }
        writeAtomic(metaFile(key), meta.toString())
    }

    fun list(): List<DocMeta> = root.listFiles { f -> f.name.endsWith(".meta.json") }.orEmpty().mapNotNull { f ->
        runCatching {
            val o = JSONObject(f.readText())
            DocMeta(o.getString("key"), o.getString("title"), o.getLong("updated"), o.optInt("pages"), o.optBoolean("hasPdf"))
        }.getOrNull()
    }.sortedByDescending { it.updated }

    fun rename(key: String, title: String) {
        val doc = load(key); doc.title = title; save(doc)
    }

    fun delete(key: String) {
        pdfFile(key).delete(); dataFile(key).delete(); metaFile(key).delete()
    }

    private fun writeAtomic(f: File, text: String) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) {
            f.delete(); tmp.renameTo(f)
        }
    }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}

/** PdfRenderer 는 한 번에 한 쪽만 열 수 있으므로 동기화해서 사용 */
class PdfSource(file: File) : AutoCloseable {
    private val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(fd)
    private var closed = false

    val pageCount get() = renderer.pageCount

    /**
     * pdf 쪽을 bitmap 에 그린다. transform 은 페이지 좌표(pt) -> 비트맵 픽셀 변환.
     * bitmap 은 미리 흰색으로 채워져 있어야 한다.
     */
    @Synchronized
    fun render(index: Int, bitmap: Bitmap, transform: Matrix): Boolean {
        if (closed || index !in 0 until renderer.pageCount) return false
        renderer.openPage(index).use { it.render(bitmap, null, transform, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
        return true
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        renderer.close(); fd.close()
    }
}
