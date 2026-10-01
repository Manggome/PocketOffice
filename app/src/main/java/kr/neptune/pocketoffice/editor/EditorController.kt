package kr.neptune.pocketoffice.editor

import android.annotation.SuppressLint
import android.content.ContentResolver
import android.content.Intent
import android.graphics.Color
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kr.neptune.pocketoffice.PocketOfficeApp
import kr.neptune.pocketoffice.core.DeviceScanner
import kr.neptune.pocketoffice.core.DocFormat
import kr.neptune.pocketoffice.core.DocIo
import kr.neptune.pocketoffice.core.EngineStore
import kr.neptune.pocketoffice.core.Snapshot
import kr.neptune.pocketoffice.core.baseName
import kr.neptune.pocketoffice.core.withExt
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** 편집 화면이 그리는 상태 */
sealed interface Phase {
    data object Preparing : Phase
    data object Loading : Phase
    data object Ready : Phase
    data class Failed(val message: String) : Phase
}

/** 편집 화면 위에 뜨는 대화상자 */
sealed interface EditorDialog {
    data class Recover(val snapshot: Snapshot) : EditorDialog
    data object ConfirmExit : EditorDialog

    /**
     * 어디에 저장할지 묻는다.
     * @param folder 고르지 않고 바로 저장할 수 있는 폴더 (모든 파일 접근이 있을 때). 없으면 위치 고르기만 된다
     */
    data class SaveTarget(val reason: String?, val suggestedName: String, val folder: File?) : EditorDialog
    data class Message(val title: String, val text: String) : EditorDialog
}

/**
 * 문서 하나를 여는 동안의 모든 일. [EditorActivity] 가 하나씩 들고 있다.
 *
 * 액티비티는 화면을 접고 펴도 다시 만들어지지 않으므로(configChanges) WebView 와 함께
 * 여기 상태를 그대로 들고 간다. 시스템이 프로세스를 내리면 다음에 [Snapshot] 으로 이어 간다.
 */
class EditorController(private val activity: EditorActivity) {

    private val app = PocketOfficeApp.instance
    private val main = Handler(Looper.getMainLooper())
    private val engine = EngineServer(activity)
    private val workDir = File(activity.cacheDir, "editor").apply { mkdirs() }

    // ------------------------------------------------------------------ 화면 상태

    var title by mutableStateOf("")
        private set
    var format by mutableStateOf<DocFormat?>(null)
        private set
    var phase by mutableStateOf<Phase>(Phase.Preparing)
        private set
    var readonly by mutableStateOf(false)
        private set
    var dialog by mutableStateOf<EditorDialog?>(null)
    /** 저장 중일 때 위쪽 막대에 띄울 말 */
    var busy by mutableStateOf<String?>(null)
        private set

    /** 편집기가 알려 준 "저장 후 고친 것이 있다" */
    private var editorDirty by mutableStateOf(false)

    /**
     * 사본·공유·내보내기도 편집기 입장에서는 "저장" 이라 고쳐짐 표시를 지운다.
     * 실제 파일에는 아직 안 들어갔으므로 여기서 따로 기억한다.
     */
    private var unsavedExport by mutableStateOf(false)

    val dirty by derivedStateOf { editorDirty || unsavedExport }

    var webView by mutableStateOf<WebView?>(null)
        private set

    /** 편집기 엔진을 아직 받지 않아 기다리는 중 */
    var waitingForEngine by mutableStateOf(false)
        private set

    // ------------------------------------------------------------------ 문서 세션

    /** 처음 연 파일. 새 문서면 null */
    private var sourceUri: Uri? = null
    /** "저장" 이 덮어쓸 곳. 아직 정해지지 않았으면(새 문서, 형식이 바뀌는 doc 등) null */
    private var targetUri: Uri? = null
    /** 편집기에 넘긴 이름. 확장자가 편집기가 읽는 형식을 정한다 */
    private var displayName = ""
    private var recoveryKey = ""
    private var backedUp = false

    private var hostReady = false
    private var openScript: String? = null
    private var closing = false
    private var finishAfterSave = false

    private sealed interface Op {
        /** 요청할 때 고친 것이 있었는가 — 파일에 안 들어가는 저장 뒤에 표시를 되살리려고 */
        val wasDirty: Boolean

        data class Overwrite(val uri: Uri, override val wasDirty: Boolean) : Op
        data class WriteTo(val uri: Uri, override val wasDirty: Boolean) : Op
        data class WriteFile(val file: File, override val wasDirty: Boolean) : Op
        data class Export(val uri: Uri, override val wasDirty: Boolean) : Op
        data class Share(override val wasDirty: Boolean) : Op
        data class Print(override val wasDirty: Boolean) : Op
        data class Snapshot(override val wasDirty: Boolean) : Op
    }

    private val pending = HashMap<String, Op>()
    private var seq = 0

    /** 위치 고르기 화면을 띄운 이유 */
    private sealed interface PickFor {
        data object SaveAs : PickFor
        data object ExportPdf : PickFor
        /** 편집기 메뉴의 "다른 형식으로 다운로드" 가 이미 만들어 준 파일 */
        data class EditorExport(val file: File) : PickFor
    }

    private var pickFor: PickFor? = null

    // ------------------------------------------------------------------ 시작

    fun start(request: DocRequest?) {
        if (request == null) {
            phase = Phase.Failed("열 문서를 찾지 못했습니다")
            return
        }
        activity.lifecycleScope.launch {
            if (!EngineStore.isReady) {
                // 처음 실행이라 엔진이 없다. Wi-Fi 면 바로 받고, 아니면 화면의 버튼을 기다린다
                waitingForEngine = true
                if (EngineStore.onUnmeteredNetwork(activity)) EngineStore.download()
                EngineStore.state.first { it == EngineStore.State.Ready }
                waitingForEngine = false
            }
            createWebView()
            when (request) {
                is DocRequest.Existing -> prepareExisting(request.uri)
                is DocRequest.New -> openNew(request)
                is DocRequest.Restore -> {
                    val snap = app.recovery.find(request.key)
                    if (snap == null) phase = Phase.Failed("복구할 사본이 없습니다") else openSnapshot(snap)
                }
            }
        }
    }

    private suspend fun prepareExisting(uri: Uri) {
        val meta = withContext(Dispatchers.IO) { runCatching { DocIo.meta(activity, uri) }.getOrNull() }
        if (meta == null) {
            phase = Phase.Failed("파일 정보를 읽지 못했습니다")
            return
        }
        val fmt = DocFormat.detect(meta.name, meta.mime)
        if (fmt == null) {
            phase = Phase.Failed("지원하지 않는 파일 형식입니다\n${meta.name}")
            return
        }
        title = meta.name
        format = fmt

        // 저장하지 않고 떠났던 사본이 있으면 먼저 묻는다
        val snap = app.recovery.find(app.recovery.keyFor(uri.toString()))
        if (snap != null) {
            dialog = EditorDialog.Recover(snap)
            pendingExisting = Triple(uri, meta.name, fmt)
            return
        }
        openExisting(uri, meta.name, fmt, meta.size)
    }

    private var pendingExisting: Triple<Uri, String, DocFormat>? = null

    /** 복구 질문의 답 */
    fun onRecoverChoice(restore: Boolean) {
        val snap = (dialog as? EditorDialog.Recover)?.snapshot ?: return
        dialog = null
        if (restore) {
            openSnapshot(snap)
            return
        }
        app.recovery.delete(snap.key)
        val (uri, name, fmt) = pendingExisting ?: return
        openExisting(uri, name, fmt, -1)
    }

    private fun openExisting(uri: Uri, name: String, fmt: DocFormat, size: Long) {
        DocIo.persist(activity, uri)
        app.recent.touch(uri, name, size)
        sourceUri = uri
        targetUri = if (fmt.convertsOnSave) null else uri
        recoveryKey = app.recovery.keyFor(uri.toString())
        load(name, fmt, readonly = app.prefs.settings.value.openInViewMode) { DocIo.openInput(activity, uri) }
    }

    private fun openNew(request: DocRequest.New) {
        val fmt = request.format
        recoveryKey = app.recovery.keyFor("new:" + request.id)
        load("새 ${fmt.kind.label}.${fmt.ext}", fmt, readonly = false) {
            activity.assets.open("templates/blank.${fmt.ext}")
        }
    }

    private fun openSnapshot(snap: Snapshot) {
        activity.lifecycleScope.launch {
            val fmt = DocFormat.fromName(snap.name) ?: DocFormat.DOCX
            val source = snap.sourceUri?.let(Uri::parse)
            val sourceFormat = source?.let { s ->
                withContext(Dispatchers.IO) { runCatching { DocIo.meta(activity, s) }.getOrNull() }
                    ?.let { DocFormat.detect(it.name, it.mime) }
            }
            sourceUri = source
            // 사본의 형식이 원본과 같을 때만 원래 자리에 덮어쓴다 (doc 의 사본은 docx 라 새로 저장해야 한다)
            targetUri = source?.takeIf { sourceFormat == fmt }
            recoveryKey = snap.key
            unsavedExport = true
            load(snap.name, fmt, readonly = false) { snap.file.inputStream() }
        }
    }

    private fun load(name: String, fmt: DocFormat, readonly: Boolean, open: () -> java.io.InputStream) {
        displayName = name
        title = name
        format = fmt
        this.readonly = readonly
        phase = Phase.Loading

        val token = UUID.randomUUID().toString()
        engine.doc = EngineServer.DocSource(token, fmt.mime, open)
        val opts = JSONObject()
            .put("url", EngineServer.docPath(token, name))
            .put("fileName", name)
            .put("saveExt", fmt.saveExt)
            .put("readonly", readonly)
        openScript = "Pocket.open($opts)"
        if (hostReady) runOpen()
    }

    private fun runOpen() {
        val script = openScript ?: return
        openScript = null
        webView?.evaluateJavascript(script, null)
    }

    // ------------------------------------------------------------------ WebView

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView() {
        WebView.setWebContentsDebuggingEnabled(true)
        val view = WebView(activity)
        view.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        view.setBackgroundColor(if (isNight()) Color.rgb(30, 30, 30) else Color.rgb(243, 243, 243))
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mediaPlaybackRequiresUserGesture = true
            // 시스템 글자 크기가 편집기 UI 를 망가뜨리지 않게
            textZoom = 100
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = false
        }
        view.addJavascriptInterface(EditorBridge(workDir, bridgeListener), EditorBridge.NAME)
        view.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(v: WebView, request: WebResourceRequest): WebResourceResponse? =
                engine.intercept(request)

            override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                if (request.url.host == EngineServer.HOST) return false
                // 문서 안의 링크는 브라우저로
                runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, request.url)) }
                return true
            }

            override fun onRenderProcessGone(v: WebView, detail: RenderProcessGoneDetail): Boolean {
                // 편집기 렌더러가 죽었다 (대개 메모리 부족). 앱까지 같이 죽지 않게 WebView 만 버린다
                Log.w(TAG, "렌더러 종료 crash=${detail.didCrash()}")
                (v.parent as? ViewGroup)?.removeView(v)
                v.destroy()
                webView = null
                phase = Phase.Failed(
                    "편집기가 멈췄습니다 (메모리가 부족했을 수 있습니다)." +
                        if (dirty) "\n고친 내용은 마지막으로 떠 둔 사본에서 복구할 수 있습니다." else ""
                )
                return true
            }
        }
        view.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                Log.d(TAG, "[js] ${message.message()} (${message.sourceId()}:${message.lineNumber()})")
                return true
            }

            // 그림 넣기 등 편집기의 파일 고르기
            override fun onShowFileChooser(
                v: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams,
            ): Boolean = activity.chooseFiles(params, callback)
        }
        webView = view
        view.loadUrl(EngineServer.HOST_PAGE)
    }

    private fun isNight(): Boolean =
        (activity.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

    private val bridgeListener = object : EditorBridge.Listener {
        override fun onEvent(type: String, payload: JSONObject) {
            main.post { handleEvent(type, payload) }
        }

        override fun onSaved(id: String, file: File, fileName: String, dirty: Boolean) {
            main.post { handleSaved(id, file, fileName, dirty) }
        }

        override fun onSaveFailed(id: String, message: String) {
            main.post { handleSaveFailed(id, message) }
        }
    }

    private fun handleEvent(type: String, payload: JSONObject) {
        when (type) {
            "ready" -> {
                hostReady = true
                runOpen()
            }
            "opened" -> {
                phase = Phase.Ready
                readonly = payload.optBoolean("readonly", readonly)
            }
            "dirty" -> editorDirty = payload.optBoolean("dirty", false)
            "readonly" -> readonly = payload.optBoolean("readonly", false)
            "requestSave" -> save()
            "error" -> {
                val message = payload.optString("message", "알 수 없는 오류")
                if (phase is Phase.Loading) {
                    phase = Phase.Failed("문서를 열지 못했습니다\n$message")
                } else {
                    toast(message)
                }
            }
        }
    }

    // ------------------------------------------------------------------ 동작

    /** 편집기에 지금 내용을 [ext] 형식으로 내보내 달라고 한다. 결과는 [handleSaved] 로 온다 */
    private fun request(op: Op, ext: String, message: String?) {
        val view = webView ?: return
        val id = "op-${++seq}-${System.currentTimeMillis()}"
        pending[id] = op
        if (message != null) busy = message
        view.evaluateJavascript("Pocket.save(${JSONObject.quote(id)}, ${JSONObject.quote(ext.uppercase())})", null)
    }

    private val ready get() = phase == Phase.Ready && webView != null

    fun save() {
        if (!ready) return
        if (pending.values.any { it !is Op.Snapshot }) return // 이미 저장 중
        val target = targetUri
        val fmt = format ?: return
        if (target == null) {
            val reason = when {
                sourceUri == null -> null
                fmt.convertsOnSave -> ".${fmt.ext} 형식은 그대로 저장할 수 없어 .${fmt.saveExt} 로 새로 저장합니다."
                else -> "원래 자리에 저장할 수 없어 새 파일로 저장합니다."
            }
            askTarget(reason)
            return
        }
        if (!dirty) {
            toast("바뀐 내용이 없습니다")
            if (finishAfterSave) finish()
            return
        }
        request(Op.Overwrite(target, dirty), fmt.saveExt, "저장하는 중…")
    }

    fun saveAs() {
        if (!ready) return
        askTarget(null)
    }

    private fun askTarget(reason: String?) {
        val fmt = format ?: return
        dialog = EditorDialog.SaveTarget(reason, withExt(displayName, fmt.saveExt), directFolder())
    }

    /**
     * 고르지 않고 바로 저장할 폴더. 모든 파일 접근이 있으면 원본 옆, 새 문서는 문서/PocketOffice.
     * 그 외에는 SAF 로 직접 고르게 한다.
     */
    private fun directFolder(): File? {
        if (!DeviceScanner.hasAccess(activity)) return null
        val src = sourceUri
        if (src != null && src.scheme == ContentResolver.SCHEME_FILE) {
            return File(src.path ?: return null).parentFile
        }
        return if (src == null) DeviceScanner.defaultFolder() else null
    }

    /** 저장 위치 대화상자: 바로 이 폴더에 */
    fun saveInto(folder: File, name: String) {
        dialog = null
        val fmt = format ?: return
        val file = uniqueFile(folder, withExt(name.trim().ifEmpty { "문서" }, fmt.saveExt))
        request(Op.WriteFile(file, dirty), fmt.saveExt, "저장하는 중…")
    }

    /** 저장 위치 대화상자: 위치 고르기 */
    fun pickLocation(name: String) {
        dialog = null
        val fmt = format ?: return
        pickFor = PickFor.SaveAs
        val finalName = withExt(name.trim().ifEmpty { "문서" }, fmt.saveExt)
        activity.createDocument(finalName, DocFormat.fromExt(fmt.saveExt)?.mime ?: fmt.mime)
    }

    fun exportPdf() {
        if (!ready) return
        pickFor = PickFor.ExportPdf
        activity.createDocument(withExt(displayName, "pdf"), DocFormat.PDF.mime)
    }

    fun share() {
        val fmt = format ?: return
        if (ready) request(Op.Share(dirty), fmt.saveExt, "공유할 파일을 만드는 중…")
    }

    fun print() {
        if (ready) request(Op.Print(dirty), "PDF", "인쇄할 PDF 를 만드는 중…")
    }

    fun toggleReadonly() {
        if (!ready) return
        webView?.evaluateJavascript("Pocket.setReadonly(${!readonly})", null)
    }

    /** 위치 고르기 결과 */
    fun onDocumentCreated(uri: Uri?) {
        val purpose = pickFor
        pickFor = null
        if (uri == null) {
            if (finishAfterSave) finishAfterSave = false
            return
        }
        DocIo.persist(activity, uri)
        when (purpose) {
            PickFor.SaveAs -> format?.let { request(Op.WriteTo(uri, dirty), it.saveExt, "저장하는 중…") }
            PickFor.ExportPdf -> request(Op.Export(uri, dirty), "PDF", "PDF 로 내보내는 중…")
            is PickFor.EditorExport -> writeOut(Op.Export(uri, false), purpose.file)
            null -> Unit
        }
    }

    private fun handleSaved(id: String, file: File, fileName: String, dirtyAfter: Boolean) {
        val op = pending.remove(id)
        if (op == null) {
            // 편집기 메뉴의 "다른 형식으로 다운로드" 등 이쪽에서 시키지 않은 내보내기.
            // 이 경로는 편집기의 고쳐짐 표시를 건드리지 않는다
            pickFor = PickFor.EditorExport(file)
            val mime = DocFormat.fromName(fileName)?.mime ?: "application/octet-stream"
            activity.createDocument(fileName.ifBlank { displayName }, mime)
            return
        }
        editorDirty = dirtyAfter
        writeOut(op, file)
    }

    private fun writeOut(op: Op, file: File) {
        activity.lifecycleScope.launch {
            when (op) {
                is Op.Overwrite -> {
                    val result = withContext(Dispatchers.IO) {
                        runCatching {
                            if (!backedUp) DocIo.backup(activity, op.uri, displayName)
                            DocIo.writeBack(activity, op.uri, file)
                        }
                    }
                    file.delete()
                    busy = null
                    result.onSuccess {
                        backedUp = true
                        afterWrite(op.uri)
                        toast("저장했습니다")
                    }.onFailure {
                        Log.w(TAG, "덮어쓰기 실패", it)
                        unsavedExport = true
                        targetUri = null
                        finishAfterSave = false
                        askTarget("이 파일은 여기서 바로 고칠 수 없습니다 (다른 앱이 읽기만 허락했거나 저장소가 막혀 있음). 새 파일로 저장해 주세요.")
                    }
                }

                is Op.WriteTo, is Op.WriteFile -> {
                    val uri = if (op is Op.WriteTo) op.uri else Uri.fromFile((op as Op.WriteFile).file)
                    val result = withContext(Dispatchers.IO) {
                        runCatching {
                            if (op is Op.WriteFile) op.file.parentFile?.mkdirs()
                            DocIo.writeBack(activity, uri, file)
                            DocIo.meta(activity, uri)
                        }
                    }
                    file.delete()
                    busy = null
                    result.onSuccess { meta ->
                        // 이제 이 파일이 "그 문서" 다. 원본은 최근 목록에 그대로 둔다
                        val oldKey = recoveryKey
                        app.recent.touch(uri, meta.name, meta.size)
                        sourceUri = uri
                        targetUri = uri
                        displayName = meta.name
                        title = meta.name
                        DocFormat.fromName(meta.name)?.let { format = it }
                        recoveryKey = app.recovery.keyFor(uri.toString())
                        app.recovery.delete(oldKey)
                        afterWrite(uri)
                        toast(if (op is Op.WriteFile) "${op.file.parentFile?.name ?: ""} 폴더에 저장했습니다" else "저장했습니다")
                    }.onFailure {
                        Log.w(TAG, "새 파일 저장 실패", it)
                        unsavedExport = true
                        finishAfterSave = false
                        dialog = EditorDialog.Message("저장하지 못했습니다", it.message ?: "알 수 없는 오류")
                    }
                }

                is Op.Export -> {
                    val result = withContext(Dispatchers.IO) { runCatching { DocIo.writeBack(activity, op.uri, file) } }
                    file.delete()
                    busy = null
                    unsavedExport = unsavedExport || op.wasDirty
                    result.onSuccess { toast("내보냈습니다") }
                        .onFailure { dialog = EditorDialog.Message("내보내지 못했습니다", it.message ?: "") }
                }

                is Op.Share -> {
                    val fmt = format ?: return@launch
                    val out = Outputs.shareFile(activity, withExt(displayName, fmt.saveExt))
                    withContext(Dispatchers.IO) { file.renameTo(out) || run { file.copyTo(out, true); file.delete() } }
                    busy = null
                    unsavedExport = unsavedExport || op.wasDirty
                    runCatching { Outputs.share(activity, out, DocFormat.fromExt(fmt.saveExt)?.mime ?: fmt.mime) }
                }

                is Op.Print -> {
                    val out = Outputs.shareFile(activity, withExt(displayName, "pdf"))
                    withContext(Dispatchers.IO) { file.renameTo(out) || run { file.copyTo(out, true); file.delete() } }
                    busy = null
                    unsavedExport = unsavedExport || op.wasDirty
                    runCatching { Outputs.print(activity, out, baseName(displayName)) }
                        .onFailure { toast("인쇄 화면을 열지 못했습니다") }
                }

                is Op.Snapshot -> {
                    val fmt = format ?: return@launch
                    withContext(Dispatchers.IO) {
                        app.recovery.put(recoveryKey, sourceUri?.toString(), withExt(displayName, fmt.saveExt), file)
                    }
                    unsavedExport = true
                }
            }
        }
    }

    /** 사용자 파일에 제대로 들어간 뒤 */
    private fun afterWrite(uri: Uri) {
        unsavedExport = false
        app.recovery.delete(recoveryKey)
        app.recent.touch(uri, displayName, -1)
        if (uri.scheme == ContentResolver.SCHEME_FILE) {
            // 다른 앱(파일 관리자, 갤러리 등)과 우리 목록에 바로 보이게
            MediaScannerConnection.scanFile(activity, arrayOf(uri.path), null, null)
        }
        if (finishAfterSave && !dirty) finish()
    }

    private fun handleSaveFailed(id: String, message: String) {
        val op = pending.remove(id)
        busy = null
        finishAfterSave = false
        if (op is Op.Snapshot || op == null) {
            Log.w(TAG, "사본 실패: $message")
            return
        }
        dialog = EditorDialog.Message("저장하지 못했습니다", message)
    }

    // ------------------------------------------------------------------ 생명주기

    /** 대화상자를 닫는다. 저장 위치를 묻다 닫으면 "저장하고 나가기" 도 취소된 것이다 */
    fun dismissDialog() {
        if (dialog is EditorDialog.SaveTarget) finishAfterSave = false
        dialog = null
    }

    fun onBack() {
        when {
            dialog != null -> dismissDialog()
            dirty && ready -> dialog = EditorDialog.ConfirmExit
            else -> finish()
        }
    }

    fun onExitChoice(save: Boolean) {
        dialog = null
        if (save) {
            finishAfterSave = true
            save()
        } else {
            app.recovery.delete(recoveryKey)
            finish()
        }
    }

    /** 화면을 벗어날 때 저장 안 된 내용을 앱 안에 떠 둔다 */
    fun onStop() {
        if (closing || !ready || !dirty || recoveryKey.isEmpty()) return
        if (pending.isNotEmpty()) return
        val fmt = format ?: return
        request(Op.Snapshot(true), fmt.saveExt, null)
    }

    fun onDestroy() {
        engine.doc = null
        webView?.let {
            (it.parent as? ViewGroup)?.removeView(it)
            it.destroy()
        }
        webView = null
    }

    private fun finish() {
        closing = true
        activity.finishAndRemoveTask()
    }

    fun close() = finish()

    private fun toast(text: String) = Toast.makeText(activity, text, Toast.LENGTH_SHORT).show()

    private fun uniqueFile(folder: File, name: String): File {
        var file = File(folder, DocIo.safeFileName(name))
        var n = 1
        val base = baseName(file.name)
        val ext = file.name.substringAfterLast('.', "")
        while (file.exists()) {
            file = File(folder, "$base ($n).$ext")
            n++
        }
        return file
    }

    private companion object {
        const val TAG = "PocketEditor"
    }
}
