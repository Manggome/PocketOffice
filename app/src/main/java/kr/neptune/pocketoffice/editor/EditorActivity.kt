package kr.neptune.pocketoffice.editor

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import kr.neptune.pocketoffice.PocketOfficeApp
import kr.neptune.pocketoffice.ui.PocketTheme

/**
 * 문서 하나의 편집 화면. 다른 앱에서 문서를 눌러도 곧장 여기로 온다.
 *
 * 화면 회전·접기·펴기에도 다시 만들어지지 않는다(manifest 의 configChanges).
 * 편집기를 새로 띄우면 고친 내용이 사라지기 때문이다.
 */
class EditorActivity : ComponentActivity() {

    private lateinit var controller: EditorController

    private val createDocumentLauncher = registerForActivityResult(CreateDocumentContract()) { uri ->
        controller.onDocumentCreated(uri)
    }

    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private val fileChooserLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data))
        fileCallback = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        controller = EditorController(this)
        val mode = DocRequest.modeOf(intent) ?: PocketOfficeApp.instance.prefs.settings.value.openMode
        controller.start(DocRequest.from(intent), mode)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = controller.onBack()
        })

        setContent {
            PocketTheme {
                EditorScreen(controller)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // 같은 문서를 또 열면 이 화면이 앞으로 나온다 (documentLaunchMode=intoExisting).
        // 다른 모드로 열었으면 그 모드로 바꾼다
        DocRequest.modeOf(intent)?.let { controller.switchMode(it) }
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) controller.onStop()
    }

    override fun onDestroy() {
        fileCallback?.onReceiveValue(null)
        fileCallback = null
        controller.onDestroy()
        super.onDestroy()
    }

    fun createDocument(name: String, mime: String) {
        runCatching { createDocumentLauncher.launch(name to mime) }
            .onFailure { controller.onDocumentCreated(null) }
    }

    fun chooseFiles(params: WebChromeClient.FileChooserParams, callback: ValueCallback<Array<Uri>>): Boolean {
        fileCallback?.onReceiveValue(null)
        fileCallback = callback
        return runCatching {
            fileChooserLauncher.launch(params.createIntent())
            true
        }.getOrElse {
            // false 를 돌려주면 WebView 가 알아서 취소로 처리한다. 콜백은 부르면 안 된다
            fileCallback = null
            false
        }
    }
}
