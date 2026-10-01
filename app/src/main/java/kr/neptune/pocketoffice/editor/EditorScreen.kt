package kr.neptune.pocketoffice.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kr.neptune.pocketoffice.core.DocKind
import kr.neptune.pocketoffice.core.baseName
import androidx.compose.foundation.layout.widthIn
import kr.neptune.pocketoffice.ui.DocBadge
import kr.neptune.pocketoffice.ui.EngineCard
import kr.neptune.pocketoffice.ui.formatWhen

@Composable
fun EditorScreen(c: EditorController) {
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            // 키보드가 올라오면 편집기가 그만큼 줄어든다 (edge-to-edge 에서는 adjustResize 가 저절로 안 된다)
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        TopBar(c)
        Box(Modifier.fillMaxWidth().height(2.dp)) {
            if (c.busy != null) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            c.webView?.let { view ->
                AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
            }
            when (val phase = c.phase) {
                Phase.Preparing, Phase.Loading -> LoadingCover(c)
                is Phase.Failed -> FailedCover(phase.message, onClose = c::close)
                Phase.Ready -> Unit
            }
        }
    }
    Dialogs(c)
}

@Composable
private fun TopBar(c: EditorController) {
    var menu by remember { mutableStateOf(false) }
    val ready = c.phase == Phase.Ready
    Row(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = c::onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
        }
        DocBadge(c.format?.kind, size = 28.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                c.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val sub = when {
                c.busy != null -> c.busy!!
                !ready -> "여는 중"
                c.readonly -> "읽기 모드"
                c.dirty -> "저장 안 됨"
                else -> "저장됨"
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (ready && c.dirty && c.busy == null) {
                    Box(Modifier.size(6.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
                    Spacer(Modifier.width(4.dp))
                }
                Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        if (ready && c.readonly) {
            FilledTonalButton(onClick = c::toggleReadonly, modifier = Modifier.padding(end = 4.dp)) {
                Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("편집")
            }
        } else {
            IconButton(onClick = c::save, enabled = ready && c.busy == null) {
                Icon(
                    Icons.Outlined.Save,
                    contentDescription = "저장",
                    tint = if (c.dirty) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Box {
            IconButton(onClick = { menu = true }, enabled = ready) {
                Icon(Icons.Outlined.MoreVert, contentDescription = "더 보기")
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("다른 이름으로 저장") }, onClick = { menu = false; c.saveAs() })
                if (c.format?.kind != DocKind.PDF) {
                    DropdownMenuItem(text = { Text("PDF 로 내보내기") }, onClick = { menu = false; c.exportPdf() })
                }
                DropdownMenuItem(text = { Text("공유") }, onClick = { menu = false; c.share() })
                DropdownMenuItem(text = { Text("인쇄") }, onClick = { menu = false; c.print() })
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(if (c.readonly) "편집 모드로" else "읽기 모드로") },
                    onClick = { menu = false; c.toggleReadonly() },
                )
            }
        }
    }
}

@Composable
private fun LoadingCover(c: EditorController) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            DocBadge(c.format?.kind, size = 64.dp)
            Spacer(Modifier.height(20.dp))
            if (c.waitingForEngine) {
                // 처음 실행: 엔진을 다 받으면 저절로 문서가 열린다
                EngineCard(Modifier.padding(horizontal = 24.dp).widthIn(max = 480.dp))
                return@Column
            }
            CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
            Spacer(Modifier.height(14.dp))
            Text("문서를 여는 중…", style = MaterialTheme.typography.bodyMedium)
            if (c.title.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    c.title,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 32.dp),
                )
            }
        }
    }
}

@Composable
private fun FailedCover(message: String, onClose: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("열 수 없습니다", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(24.dp))
            Button(onClick = onClose) { Text("닫기") }
        }
    }
}

@Composable
private fun Dialogs(c: EditorController) {
    when (val d = c.dialog) {
        null -> Unit

        is EditorDialog.Recover -> AlertDialog(
            onDismissRequest = {},
            title = { Text("저장하지 않은 내용이 있습니다") },
            text = {
                Text("${formatWhen(d.snapshot.savedAt)}에 저장하지 않고 닫은 내용이 남아 있습니다. 이어서 편집할까요?")
            },
            confirmButton = { TextButton(onClick = { c.onRecoverChoice(true) }) { Text("이어서 편집") } },
            dismissButton = { TextButton(onClick = { c.onRecoverChoice(false) }) { Text("버리고 원본 열기") } },
        )

        EditorDialog.ConfirmExit -> AlertDialog(
            onDismissRequest = c::dismissDialog,
            title = { Text("저장할까요?") },
            text = { Text("고친 내용이 아직 파일에 저장되지 않았습니다.") },
            confirmButton = { TextButton(onClick = { c.onExitChoice(true) }) { Text("저장") } },
            dismissButton = {
                Row {
                    TextButton(onClick = { c.onExitChoice(false) }) { Text("저장 안 함") }
                    TextButton(onClick = c::dismissDialog) { Text("취소") }
                }
            },
        )

        is EditorDialog.SaveTarget -> SaveTargetDialog(c, d)

        is EditorDialog.Message -> AlertDialog(
            onDismissRequest = c::dismissDialog,
            title = { Text(d.title) },
            text = { Text(d.text) },
            confirmButton = { TextButton(onClick = c::dismissDialog) { Text("확인") } },
        )
    }
}

@Composable
private fun SaveTargetDialog(c: EditorController, d: EditorDialog.SaveTarget) {
    val ext = d.suggestedName.substringAfterLast('.', "")
    var name by remember(d) { mutableStateOf(baseName(d.suggestedName)) }
    AlertDialog(
        onDismissRequest = c::dismissDialog,
        title = { Text("저장") },
        text = {
            Column {
                if (d.reason != null) {
                    Text(d.reason, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(12.dp))
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("파일 이름") },
                    suffix = { Text(".$ext") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (d.folder != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "저장 위치: ${d.folder.path.substringAfter("/0/", d.folder.path)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            if (d.folder != null) {
                TextButton(onClick = { c.saveInto(d.folder, "$name.$ext") }) { Text("저장") }
            } else {
                TextButton(onClick = { c.pickLocation("$name.$ext") }) { Text("위치 고르기") }
            }
        },
        dismissButton = {
            Row {
                if (d.folder != null) {
                    TextButton(onClick = { c.pickLocation("$name.$ext") }) { Text("다른 위치") }
                }
                TextButton(onClick = c::dismissDialog) { Text("취소") }
            }
        },
    )
}
