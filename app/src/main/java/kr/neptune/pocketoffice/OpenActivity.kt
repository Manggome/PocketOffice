package kr.neptune.pocketoffice

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import kr.neptune.pocketoffice.core.OpenMode
import kr.neptune.pocketoffice.editor.DocRequest

/**
 * 다른 앱(파일 관리자, 카카오톡, 메일)에서 문서를 눌렀을 때 들어오는 문.
 * 화면 없이 첫 화면의 "보기 / 편집" 설정대로 뷰어나 편집기로 넘기고 바로 닫힌다.
 * "편집" 으로 보낸 요청(ACTION_EDIT)은 설정과 관계없이 편집기로 연다.
 */
class OpenActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val request = DocRequest.from(intent)
        if (request == null) {
            Toast.makeText(this, "열 문서를 찾지 못했습니다", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val mode = if (intent.action == Intent.ACTION_EDIT) OpenMode.EDIT
        else PocketOfficeApp.instance.prefs.settings.value.openMode
        runCatching { startActivity(DocRequest.intent(this, request, mode)) }
            .onFailure { Toast.makeText(this, "문서를 열 수 없습니다: ${it.message}", Toast.LENGTH_LONG).show() }
        finish()
    }
}
