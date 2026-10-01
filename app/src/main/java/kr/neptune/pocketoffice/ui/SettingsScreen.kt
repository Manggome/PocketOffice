package kr.neptune.pocketoffice.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import kr.neptune.pocketoffice.core.AppUpdater
import kr.neptune.pocketoffice.core.DocIo
import kr.neptune.pocketoffice.core.EngineStore

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: HomeViewModel, onBack: () -> Unit, onRequestAccess: () -> Unit) {
    val settings by vm.prefs.settings.collectAsStateWithLifecycle()
    val hasAccess by vm.hasAccess.collectAsStateWithLifecycle()
    val update by AppUpdater.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val engine by EngineStore.state.collectAsStateWithLifecycle()
    val backups = remember { DocIo.backupDir(context).listFiles()?.size ?: 0 }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("설정") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
                Section("문서 열기")
                SwitchRow(
                    title = "읽기 모드로 열기",
                    caption = "문서를 열면 보기만 합니다. 위쪽 '편집' 을 눌러야 고칠 수 있습니다.",
                    checked = settings.openInViewMode,
                    onChange = { on -> vm.prefs.update { it.copy(openInViewMode = on) } },
                )

                Section("내 폰의 문서")
                Text(
                    if (hasAccess) "모든 파일 접근이 허용되어 있습니다. '내 폰' 탭에서 문서를 모아 보고, 원본 옆에 바로 저장합니다."
                    else "허용하면 '내 폰' 탭에서 폰 안의 문서를 한 번에 보고, 새 문서를 고르는 과정 없이 바로 저장합니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onRequestAccess) { Text(if (hasAccess) "권한 설정 열기" else "모든 파일 접근 허용") }

                Section("앱 업데이트")
                Text("현재 버전 ${AppUpdater.currentVersionName} (${AppUpdater.currentVersionCode})", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = { scope.launch { AppUpdater.check(silent = false) } },
                        enabled = update !is AppUpdater.State.Checking && update !is AppUpdater.State.Downloading,
                    ) { Text("업데이트 확인") }
                    when (val s = update) {
                        AppUpdater.State.Checking -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        AppUpdater.State.UpToDate -> Caption("최신 버전입니다")
                        is AppUpdater.State.Failed -> Caption("확인하지 못했습니다: ${s.message}")
                        else -> Unit
                    }
                }
                SwitchRow(
                    title = "앱을 켤 때 새 버전 확인",
                    caption = "새 버전이 있을 때만 알려 줍니다. 받기와 설치는 앱 안에서 이어집니다.",
                    checked = settings.autoUpdateCheck,
                    onChange = { on -> vm.prefs.update { it.copy(autoUpdateCheck = on) } },
                )

                Section("보관")
                Text(
                    "파일을 처음 덮어쓸 때 원본을 앱 안에 한 부 남겨 둡니다 (최근 15개, 지금 ${backups}개).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { vm.recent.clear() }) { Text("최근 목록 비우기 (즐겨찾기는 남김)") }

                Section("정보")
                Caption("편집 엔진: ONLYOFFICE Docs (AGPL-3.0) · ranuts/document (AGPL-3.0)")
                Caption("엔진: ${EngineStore.id} · " + if (engine == EngineStore.State.Ready) "받아 둠" else "아직 받지 않음")
                Caption("문서는 기기 밖으로 나가지 않습니다. 인터넷은 앱 업데이트 확인에만 씁니다.")
                Spacer(Modifier.height(8.dp))
                Text(
                    "소스 코드 보기",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SOURCE_URL))) }
                    }.padding(vertical = 6.dp),
                )
                Spacer(Modifier.height(40.dp))
            }
        }
    }
}

private const val SOURCE_URL = "https://github.com/Manggome/PocketOffice"

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(20.dp))
    HorizontalDivider()
    Spacer(Modifier.height(14.dp))
    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun Caption(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SwitchRow(title: String, caption: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Caption(caption)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
