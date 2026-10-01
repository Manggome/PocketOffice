package kr.neptune.pocketoffice.editor

import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * host.js 가 부르는 `window.PocketNative`.
 *
 * 메서드는 WebView 의 JavaBridge 스레드에서 불린다. 파일 쓰기는 여기서 바로 하고,
 * 화면에 알릴 것만 [Listener] 로 넘긴다 (받는 쪽이 메인 스레드로 옮긴다).
 */
class EditorBridge(
    private val workDir: File,
    private val listener: Listener,
) {

    interface Listener {
        fun onEvent(type: String, payload: JSONObject)
        fun onSaved(id: String, file: File, fileName: String, dirty: Boolean)
        fun onSaveFailed(id: String, message: String)
    }

    private class Part(val file: File, val name: String, val out: FileOutputStream)

    private val parts = ConcurrentHashMap<String, Part>()

    @JavascriptInterface
    fun log(message: String) {
        Log.i(TAG, message)
    }

    @JavascriptInterface
    fun onEvent(type: String, json: String) {
        val payload = runCatching { JSONObject(json) }.getOrDefault(JSONObject())
        listener.onEvent(type, payload)
    }

    @JavascriptInterface
    fun saveBegin(id: String, fileName: String, mime: String, size: Long) {
        workDir.mkdirs()
        abort(id)
        val file = File(workDir, "out-" + id.filter { it.isLetterOrDigit() || it == '-' })
        parts[id] = Part(file, fileName, FileOutputStream(file))
        Log.i(TAG, "저장 시작 $id $fileName ($size bytes, $mime)")
    }

    @JavascriptInterface
    fun saveChunk(id: String, base64: String) {
        val part = parts[id] ?: return
        try {
            part.out.write(Base64.decode(base64, Base64.DEFAULT))
        } catch (t: Throwable) {
            abort(id)
            listener.onSaveFailed(id, "저장한 내용을 받지 못했습니다: ${t.message}")
        }
    }

    @JavascriptInterface
    fun saveEnd(id: String, dirty: Boolean) {
        val part = parts.remove(id) ?: return
        runCatching { part.out.close() }
        if (part.file.length() == 0L) {
            part.file.delete()
            listener.onSaveFailed(id, "편집기가 빈 파일을 돌려줬습니다")
            return
        }
        listener.onSaved(id, part.file, part.name, dirty)
    }

    @JavascriptInterface
    fun saveFailed(id: String, message: String) {
        abort(id)
        listener.onSaveFailed(id, message)
    }

    private fun abort(id: String) {
        parts.remove(id)?.let {
            runCatching { it.out.close() }
            it.file.delete()
        }
    }

    companion object {
        private const val TAG = "PocketEditor"
        const val NAME = "PocketNative"
    }
}
