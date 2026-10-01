package kr.neptune.pocketoffice

import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kr.neptune.pocketoffice.core.DeviceDoc
import kr.neptune.pocketoffice.core.DeviceScanner
import kr.neptune.pocketoffice.core.DocFormat
import kr.neptune.pocketoffice.core.DocIo
import kr.neptune.pocketoffice.core.OpenMode
import kr.neptune.pocketoffice.core.RecentDoc
import kr.neptune.pocketoffice.core.Snapshot
import kr.neptune.pocketoffice.editor.DocRequest
import kr.neptune.pocketoffice.ui.HomeActions
import kr.neptune.pocketoffice.ui.HomeScreen
import kr.neptune.pocketoffice.ui.HomeViewModel
import kr.neptune.pocketoffice.ui.PocketTheme
import kr.neptune.pocketoffice.ui.SettingsScreen
import kr.neptune.pocketoffice.ui.UpdateDialog

/** 첫 화면: 새로 만들기, 최근 문서, 내 폰의 문서, 설정 */
class MainActivity : ComponentActivity(), HomeActions {

    private val vm: HomeViewModel by viewModels()

    private val pickDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            // 다음에 최근 목록에서 다시 열 수 있게 권한을 붙잡아 둔다
            DocIo.persist(this, uri)
            open(DocRequest.Existing(uri))
        }
    }

    private val legacyPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        vm.onResume()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            PocketTheme {
                var settingsOpen by rememberSaveable { mutableStateOf(false) }
                if (settingsOpen) {
                    BackHandler { settingsOpen = false }
                    SettingsScreen(vm, onBack = { settingsOpen = false }, onRequestAccess = ::requestAccess)
                } else {
                    HomeScreen(vm, object : HomeActions by this@MainActivity {
                        override fun openSettings() {
                            settingsOpen = true
                        }
                    })
                }
                UpdateDialog()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        vm.onResume()
    }

    override fun onPostResume() {
        super.onPostResume()
        // 첫 화면이 다 그려진 뒤 한가할 때 WebView 엔진을 미리 깨워 둔다.
        // 처음 WebView 를 만들 때 크로미움을 올리느라 걸리는 시간을 문서를 열 때 치르지 않게.
        if (!webViewWarmed) {
            webViewWarmed = true
            android.os.Looper.myQueue().addIdleHandler {
                runCatching { android.webkit.WebSettings.getDefaultUserAgent(applicationContext) }
                false
            }
        }
    }

    private fun open(request: DocRequest, mode: OpenMode = vm.prefs.settings.value.openMode, name: String? = null) {
        runCatching { startActivity(DocRequest.intent(this, request, mode, name)) }
            .onFailure { toast("문서를 열 수 없습니다: ${it.message}") }
    }

    // ------------------------------------------------------------------ HomeActions

    override fun openPicker() {
        runCatching { pickDocument.launch(DocFormat.pickerMimes) }
            .onFailure { toast("파일 고르기 화면을 열 수 없습니다") }
    }

    override fun openRecent(doc: RecentDoc, mode: OpenMode) {
        lifecycleScope.launch {
            val uri = doc.parsedUri
            val ok = withContext(Dispatchers.IO) { DocIo.isReachable(this@MainActivity, uri) }
            if (ok) {
                open(DocRequest.Existing(uri), mode, doc.name)
            } else {
                // 다른 앱이 잠깐만 빌려준 파일이거나, 지워졌거나 옮겨졌다
                toast("파일을 열 수 없습니다. 옮겨졌거나 지워졌을 수 있습니다.")
            }
        }
    }

    override fun openDevice(doc: DeviceDoc, mode: OpenMode) = open(DocRequest.Existing(doc.uri), mode, doc.name)

    override fun createNew(format: DocFormat) = open(DocRequest.newDocument(format), OpenMode.EDIT)

    override fun restore(snapshot: Snapshot) = open(DocRequest.Restore(snapshot.key), OpenMode.EDIT)

    override fun requestAccess() {
        val intent = DeviceScanner.accessSettingsIntent(this)
        if (intent != null) {
            runCatching { startActivity(intent) }
                .onFailure { toast("설정 화면을 열 수 없습니다") }
        } else {
            legacyPermission.launch(DeviceScanner.legacyPermissions)
        }
    }

    override fun openSettings() = Unit // HomeScreen 쪽에서 덮어쓴다

    private companion object {
        var webViewWarmed = false
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
}
