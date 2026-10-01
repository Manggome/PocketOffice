package kr.neptune.pocketoffice.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import kr.neptune.pocketoffice.core.AppUpdater

/**
 * 새 버전 안내 → 받기 → 설치 화면까지 한 대화상자에서 이어 간다.
 * 앱을 켤 때 조용히 확인해서 새 버전이 있을 때만 뜬다. 설정에서 직접 확인해도 같은 것이 뜬다.
 */
@Composable
fun UpdateDialog() {
    val state by AppUpdater.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    fun startInstall(file: java.io.File) {
        if (AppUpdater.canInstall(context)) {
            AppUpdater.install(context, file)
        } else {
            // 처음 한 번은 "이 앱에서 설치 허용" 을 켜야 한다. 켜고 돌아오면 다시 누르면 된다
            context.startActivity(AppUpdater.installPermissionIntent(context))
        }
    }

    when (val s = state) {
        is AppUpdater.State.Available -> AlertDialog(
            onDismissRequest = AppUpdater::dismiss,
            title = { Text("새 버전이 있습니다") },
            text = {
                Column {
                    Text("${AppUpdater.currentVersionName} → ${s.release.versionName}")
                    if (s.release.notes.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(s.release.notes, style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "앱 안에서 받아 그대로 덮어써 설치합니다. 최근 문서와 설정은 남습니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { scope.launch { AppUpdater.download(context) } }) { Text("업데이트") }
            },
            dismissButton = { TextButton(onClick = AppUpdater::dismiss) { Text("나중에") } },
        )

        is AppUpdater.State.Downloading -> AlertDialog(
            onDismissRequest = {},
            title = { Text("받는 중… ${s.percent}%") },
            text = { LinearProgressIndicator(progress = { s.percent / 100f }, modifier = Modifier.fillMaxWidth()) },
            confirmButton = {},
        )

        is AppUpdater.State.ReadyToInstall -> {
            // 다 받으면 곧바로 설치 화면을 띄운다. 허용이 꺼져 있었으면 대화상자의 버튼으로 다시 시도
            LaunchedEffect(s.file) { startInstall(s.file) }
            AlertDialog(
                onDismissRequest = AppUpdater::dismiss,
                title = { Text("설치 준비 완료") },
                text = {
                    Text(
                        if (AppUpdater.canInstall(context)) "설치 화면에서 '업데이트' 를 눌러 주세요."
                        else "설정에서 '이 출처 허용' 을 켠 뒤 돌아와 설치를 눌러 주세요."
                    )
                },
                confirmButton = { TextButton(onClick = { startInstall(s.file) }) { Text("설치") } },
                dismissButton = { TextButton(onClick = AppUpdater::dismiss) { Text("닫기") } },
            )
        }

        else -> Unit
    }
}
