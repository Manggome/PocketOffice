package kr.neptune.pocketoffice.viewer

import android.content.ContentResolver
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Row
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
import kr.neptune.pocketoffice.core.baseName
import kr.neptune.pocketoffice.editor.CreateDocumentContract
import kr.neptune.pocketoffice.editor.DocRequest
import kr.neptune.pocketoffice.editor.Outputs
import kr.neptune.pocketoffice.ui.PocketTheme
import java.io.File

/**
 * PDF 보기 전용 화면. 편집기 엔진을 띄우지 않고 안드로이드 기본 렌더러로 바로 그린다.
 * 위쪽 "필기" 로 펜·형광펜·글자를 얹어 저장할 수 있다. 양식 채우기처럼 더 깊은 편집은
 * ⋮ > 고급 편집기(OnlyOffice)로.
 */
class PdfViewerActivity : ComponentActivity() {

    private val app get() = PocketOfficeApp.instance

    private var doc by mutableStateOf<PdfDoc?>(null)
    private var error by mutableStateOf<String?>(null)
    private var title by mutableStateOf("")
    private var uri: Uri? = null

    private val ink = InkState()
    private var inking by mutableStateOf(false)
    private var saving by mutableStateOf(false)
    private var askDiscard by mutableStateOf(false)
    private var backedUp = false

    /** 원래 자리에 못 써서 위치를 고르는 동안 들고 있는 결과 */
    private var pendingOut: File? = null

    private val createDocument = registerForActivityResult(CreateDocumentContract()) { target ->
        val out = pendingOut
        pendingOut = null
        if (target == null || out == null) {
            saving = false
            return@registerForActivityResult
        }
        DocIo.persist(this, target)
        lifecycleScope.launch { writeAndReload(target, out, backup = false) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        uri = intent.data
        open()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    saving -> Unit
                    ink.textDialog != null -> ink.textDialog = null
                    inking -> actions.cancelInk()
                    else -> finishAndRemoveTask()
                }
            }
        })

        setContent {
            PocketTheme {
                val settings by app.prefs.settings.collectAsStateWithLifecycle()
                PdfViewerScreen(
                    title = title,
                    doc = doc,
                    error = error,
                    night = settings.pdfNight,
                    ink = ink,
                    inking = inking,
                    saving = saving,
                    actions = actions,
                )
                if (askDiscard) {
                    AlertDialog(
                        onDismissRequest = { askDiscard = false },
                        title = { Text("필기를 저장할까요?") },
                        text = { Text("저장하지 않으면 지금 쓴 필기가 사라집니다.") },
                        confirmButton = {
                            TextButton(onClick = {
                                askDiscard = false
                                actions.saveInk()
                            }) { Text("저장") }
                        },
                        dismissButton = {
                            Row {
                                TextButton(onClick = {
                                    askDiscard = false
                                    ink.clear()
                                    inking = false
                                }) { Text("버리기") }
                                TextButton(onClick = { askDiscard = false }) { Text("취소") }
                            }
                        },
                    )
                }
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
        override fun back() {
            if (inking) cancelInk() else finishAndRemoveTask()
        }

        override fun openAdvancedEditor() {
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

        override fun startInk() {
            if (doc == null) return
            inking = true
        }

        override fun cancelInk() {
            if (ink.isEmpty) {
                ink.clear()
                inking = false
            } else {
                askDiscard = true
            }
        }

        override fun saveInk() {
            val target = uri ?: return
            val marks = ink.marks.toList()
            if (marks.isEmpty()) {
                inking = false
                return
            }
            saving = true
            lifecycleScope.launch {
                val made = withContext(Dispatchers.IO) {
                    runCatching {
                        val dir = File(cacheDir, "viewer").apply { mkdirs() }
                        val input = File(dir, "ink-in.pdf")
                        DocIo.openInput(this@PdfViewerActivity, target).use { i -> input.outputStream().use { i.copyTo(it) } }
                        val out = File(dir, "ink-out-${System.currentTimeMillis()}.pdf")
                        PdfAnnotator.apply(this@PdfViewerActivity, input, out, marks)
                        input.delete()
                        out
                    }
                }
                val out = made.getOrElse {
                    saving = false
                    toast("필기를 저장하지 못했습니다: ${it.message}")
                    return@launch
                }
                writeAndReload(target, out, backup = true)
            }
        }
    }

    /**
     * 만든 PDF 로 [target] 을 덮어쓰고 다시 연다. 못 쓰는 자리면(다른 앱이 읽기만 허락) 새로 저장할 곳을 묻는다.
     * 덮어쓰는 동안 기존 렌더러가 줄어든 파일을 읽지 않게 먼저 닫는다 (이미 그려 둔 쪽은 그대로 보인다).
     */
    private suspend fun writeAndReload(target: Uri, out: File, backup: Boolean) {
        val old = doc
        old?.close()
        val written = withContext(Dispatchers.IO) {
            runCatching {
                if (backup && !backedUp) DocIo.backup(this@PdfViewerActivity, target, title)
                DocIo.writeBack(this@PdfViewerActivity, target, out)
            }
        }
        if (written.isFailure) {
            // 원본을 다시 열어 두고, 새 파일로 저장할 곳을 묻는다
            runCatching { doc = PdfDoc.open(this, uri ?: target) }
            pendingOut = out
            toast("이 파일은 여기서 바로 고칠 수 없어 새 파일로 저장합니다")
            runCatching { createDocument.launch("${baseName(title)} (필기).pdf" to DocFormat.PDF.mime) }
                .onFailure { saving = false }
            return
        }
        if (backup) backedUp = true
        if (target.scheme == ContentResolver.SCHEME_FILE) {
            MediaScannerConnection.scanFile(this, arrayOf(target.path), null, null)
        }
        val reopened = runCatching { PdfDoc.open(this, target) }
        out.delete()
        reopened.onSuccess { doc = it }.onFailure { error = "저장했지만 다시 열지 못했습니다\n${it.message}" }
        if (target != uri) {
            uri = target
            withContext(Dispatchers.IO) { runCatching { DocIo.meta(this@PdfViewerActivity, target) }.getOrNull() }?.let {
                title = it.name
                app.recent.touch(target, it.name, it.size)
            }
        }
        ink.clear()
        inking = false
        saving = false
        toast("필기를 저장했습니다")
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
