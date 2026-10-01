package kr.neptune.pocketoffice.viewer

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kr.neptune.pocketoffice.PocketOfficeApp
import kr.neptune.pocketoffice.core.DocFormat
import kr.neptune.pocketoffice.core.DocIo
import kr.neptune.pocketoffice.core.OpenMode
import kr.neptune.pocketoffice.editor.DocRequest
import kr.neptune.pocketoffice.editor.Outputs
import kr.neptune.pocketoffice.ui.PocketTheme

/**
 * PDF 보기 전용 화면. 편집기 엔진을 띄우지 않고 안드로이드 기본 렌더러로 바로 그린다.
 * 위쪽 "편집" 을 누르면 편집기(주석·양식·텍스트 편집)로 넘어간다.
 */
class PdfViewerActivity : ComponentActivity() {

    private val app get() = PocketOfficeApp.instance

    private var doc by mutableStateOf<PdfDoc?>(null)
    private var error by mutableStateOf<String?>(null)
    private var title by mutableStateOf("")
    private var uri: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        uri = intent.data
        open()

        setContent {
            PocketTheme {
                val settings by app.prefs.settings.collectAsStateWithLifecycle()
                PdfViewerScreen(
                    title = title,
                    doc = doc,
                    error = error,
                    night = settings.pdfNight,
                    actions = actions,
                )
            }
        }
    }

    private fun open() {
        val target = uri ?: run {
            error = "열 문서를 찾지 못했습니다"
            return
        }
        lifecycleScope.launch {
            val meta = withContext(Dispatchers.IO) { runCatching { DocIo.meta(this@PdfViewerActivity, target) }.getOrNull() }
            title = meta?.name ?: "PDF"
            if (meta != null) {
                DocIo.persist(this@PdfViewerActivity, target)
                app.recent.touch(target, meta.name, meta.size)
            }
            try {
                doc = PdfDoc.open(this@PdfViewerActivity, target)
            } catch (e: PdfPasswordException) {
                error = "암호가 걸린 PDF 라 기본 뷰어로는 열 수 없습니다. 편집기로 열면 암호를 넣을 수 있습니다."
            } catch (t: Throwable) {
                error = "PDF 를 열지 못했습니다\n${t.message ?: ""}"
            }
        }
    }

    private val actions = object : PdfViewerActions {
        override fun back() = finishAndRemoveTask()

        override fun edit() {
            val target = uri ?: return
            runCatching {
                startActivity(DocRequest.intent(this@PdfViewerActivity, DocRequest.Existing(target), OpenMode.EDIT, title))
                finishAndRemoveTask()
            }.onFailure { toast("편집기를 열 수 없습니다") }
        }

        override fun share() {
            val target = uri ?: return
            if (target.scheme == ContentResolver.SCHEME_CONTENT) {
                // 갖고 있는 읽기 권한을 그대로 넘겨 원본을 공유한다
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = DocFormat.PDF.mime
                    putExtra(Intent.EXTRA_STREAM, target)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                runCatching { startActivity(Intent.createChooser(send, title)) }
                return
            }
            lifecycleScope.launch {
                val out = copyForOutput() ?: return@launch
                runCatching { Outputs.share(this@PdfViewerActivity, out, DocFormat.PDF.mime) }
            }
        }

        override fun print() {
            lifecycleScope.launch {
                val out = copyForOutput() ?: return@launch
                runCatching { Outputs.print(this@PdfViewerActivity, out, title.removeSuffix(".pdf")) }
                    .onFailure { toast("인쇄 화면을 열지 못했습니다") }
            }
        }

        override fun setNight(on: Boolean) = app.prefs.update { it.copy(pdfNight = on) }
    }

    /** 공유·인쇄용으로 앱 캐시에 한 부 복사한다 */
    private suspend fun copyForOutput() = withContext(Dispatchers.IO) {
        val target = uri ?: return@withContext null
        runCatching {
            val out = Outputs.shareFile(this@PdfViewerActivity, title.ifBlank { "문서.pdf" })
            DocIo.openInput(this@PdfViewerActivity, target).use { input -> out.outputStream().use { input.copyTo(it) } }
            out
        }.getOrNull()
    }

    override fun onDestroy() {
        doc?.close()
        doc = null
        super.onDestroy()
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
}
