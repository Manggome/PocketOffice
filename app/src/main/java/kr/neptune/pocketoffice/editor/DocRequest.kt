package kr.neptune.pocketoffice.editor

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import kr.neptune.pocketoffice.core.DocFormat
import java.util.UUID

/** 편집 화면을 무엇으로 열지 */
sealed interface DocRequest {
    /** 폰에 있는 파일 */
    data class Existing(val uri: Uri) : DocRequest

    /** 빈 문서에서 시작. [id] 는 복구 사본을 찾는 열쇠 */
    data class New(val format: DocFormat, val id: String) : DocRequest

    /** 저장하지 않고 떠난 문서의 사본 */
    data class Restore(val key: String) : DocRequest

    companion object {
        private const val SCHEME = "pocketoffice"

        /**
         * 문서마다 최근 앱 목록에 따로 뜨게 하려고(documentLaunchMode) 새 문서·복구에도
         * 고유한 data uri 를 붙인다. 이 uri 는 앱 안에서만 쓰이고 밖으로 나가지 않는다.
         */
        fun intent(context: Context, request: DocRequest): Intent {
            val data = when (request) {
                is Existing -> request.uri
                is New -> Uri.parse("$SCHEME://new/${request.id}?ext=${request.format.ext}")
                is Restore -> Uri.parse("$SCHEME://restore/${request.key}")
            }
            return Intent(context, EditorActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                setData(data)
                if (request is Existing) {
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                }
                addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
            }
        }

        fun newDocument(format: DocFormat) = New(format, UUID.randomUUID().toString())

        fun from(intent: Intent?): DocRequest? {
            intent ?: return null
            if (intent.action == Intent.ACTION_SEND) {
                val stream = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
                return stream?.let { Existing(it) }
            }
            val data = intent.data ?: return null
            if (data.scheme == SCHEME) {
                val id = data.lastPathSegment ?: return null
                return when (data.host) {
                    "new" -> DocFormat.fromExt(data.getQueryParameter("ext"))?.let { New(it, id) }
                    "restore" -> Restore(id)
                    else -> null
                }
            }
            return Existing(data)
        }
    }
}
