package kr.neptune.pocketoffice.editor

import android.content.Context
import android.net.Uri
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import kr.neptune.pocketoffice.core.EngineStore
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import java.io.InputStream
import java.util.Locale

/**
 * WebView 의 요청을 가로채 APK 안의 파일로 답한다. 편집기에 필요한 것은 전부 기기 안에 있다.
 *
 *   https://appassets.androidplatform.net/__pocket/doc/<토큰>/<이름>   지금 열린 문서의 바이트
 *   https://appassets.androidplatform.net/__pocket/<파일>             assets/shell (다리 페이지)
 *   https://appassets.androidplatform.net/<경로>                      받아 둔 엔진 zip (편집기)
 *
 * 편집기는 상대 경로와 절대 경로(/sdkjs/...)를 섞어 쓰므로 엔진을 출처의 맨 위에 둔다.
 * 다른 주소로 나가는 요청은 막는다. 문서 안의 외부 이미지 같은 것이 몰래 밖으로 새지 않게.
 */
class EngineServer(context: Context) {

    /** 편집기에 넘길 문서 하나 */
    class DocSource(
        val token: String,
        val mime: String,
        val open: () -> InputStream,
    )

    private val assets = context.applicationContext.assets

    @Volatile
    var doc: DocSource? = null

    fun intercept(request: WebResourceRequest): WebResourceResponse? {
        val url = request.url
        val scheme = url.scheme?.lowercase(Locale.ROOT)
        if (scheme != "http" && scheme != "https") return null // data:, blob: 은 WebView 가 직접 처리
        if (url.host != HOST) return status(403, "Blocked")

        val path = url.path ?: "/"
        if (path.contains("..")) return status(400, "Bad Request")

        return when {
            path.startsWith(DOC_PREFIX) -> serveDoc(path)
            path.startsWith(SHELL_PREFIX) -> serveAsset("shell/" + path.removePrefix(SHELL_PREFIX))
            else -> serveEngine(path.removePrefix("/").ifEmpty { "editor.html" })
        }
    }

    /** 따로 받아 둔 엔진 zip 에서 꺼낸다 (core/EngineStore) */
    private fun serveEngine(enginePath: String): WebResourceResponse {
        val stream = try {
            EngineStore.open(enginePath)
        } catch (t: Throwable) {
            Log.w(TAG, "엔진 읽기 실패: $enginePath", t)
            return status(500, "Error")
        } ?: return status(404, "Not Found")
        val (mime, encoding) = mimeFor("engine/$enginePath")
        return WebResourceResponse(mime, encoding, 200, "OK", NO_CACHE, stream)
    }

    private fun serveDoc(path: String): WebResourceResponse {
        val current = doc ?: return status(404, "Not Found")
        val token = path.removePrefix(DOC_PREFIX).substringBefore('/')
        if (token != current.token) return status(404, "Not Found")
        return try {
            WebResourceResponse(current.mime, null, 200, "OK", NO_CACHE, current.open())
        } catch (t: Throwable) {
            Log.w(TAG, "문서를 열지 못했습니다", t)
            status(500, "Cannot Read")
        }
    }

    private fun serveAsset(assetPath: String): WebResourceResponse {
        val stream = try {
            assets.open(assetPath)
        } catch (e: FileNotFoundException) {
            return status(404, "Not Found")
        } catch (t: Throwable) {
            Log.w(TAG, "asset 읽기 실패: $assetPath", t)
            return status(500, "Error")
        }
        val (mime, encoding) = mimeFor(assetPath)
        return WebResourceResponse(mime, encoding, 200, "OK", NO_CACHE, stream)
    }

    private fun status(code: Int, reason: String) =
        WebResourceResponse("text/plain", "utf-8", code, reason, NO_CACHE, ByteArrayInputStream(ByteArray(0)))

    companion object {
        private const val TAG = "EngineServer"
        const val HOST = "appassets.androidplatform.net"
        const val ORIGIN = "https://$HOST"
        private const val DOC_PREFIX = "/__pocket/doc/"
        private const val SHELL_PREFIX = "/__pocket/"

        /** 다리 페이지 주소 */
        const val HOST_PAGE = "$ORIGIN/__pocket/host.html?locale=ko"

        fun docPath(token: String, fileName: String): String = DOC_PREFIX + token + "/" + Uri.encode(fileName)

        private val NO_CACHE = mapOf("Cache-Control" to "no-store")

        // tools/dev-server.mjs 의 MIME 표와 맞춰 둔다
        private val MIME = mapOf(
            "html" to "text/html",
            "js" to "text/javascript",
            "mjs" to "text/javascript",
            "css" to "text/css",
            "json" to "application/json",
            "svg" to "image/svg+xml",
            "png" to "image/png",
            "jpg" to "image/jpeg",
            "jpeg" to "image/jpeg",
            "gif" to "image/gif",
            "ico" to "image/x-icon",
            "woff" to "font/woff",
            "woff2" to "font/woff2",
            "ttf" to "font/ttf",
            "wasm" to "application/wasm",
            // x2t.wasm.br 은 prepare-engine 이 미리 풀어 둔 wasm 이다
            "br" to "application/wasm",
            "txt" to "text/plain",
            "xml" to "application/xml",
        )
        private val TEXT = setOf("html", "js", "mjs", "css", "json", "svg", "txt", "xml")

        fun mimeFor(path: String): Pair<String, String?> {
            val name = path.substringAfterLast('/')
            val ext = if (name.contains('.')) name.substringAfterLast('.').lowercase(Locale.ROOT) else ""
            val mime = MIME[ext] ?: if (path.startsWith("engine/fonts/")) "font/ttf" else "application/octet-stream"
            return mime to (if (ext in TEXT) "utf-8" else null)
        }
    }
}
