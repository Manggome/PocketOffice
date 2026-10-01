package kr.neptune.pocketoffice.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kr.neptune.pocketoffice.core.EngineStore

/**
 * 편집기 엔진을 받는 상태. 받아 둔 뒤에는 아무것도 그리지 않는다.
 * 처음 한 번만 받으면 이후로는 인터넷 없이 동작한다.
 */
@Composable
fun EngineCard(modifier: Modifier = Modifier) {
    val state by EngineStore.state.collectAsStateWithLifecycle()
    if (state == EngineStore.State.Ready) return

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(18.dp)) {
            Text("편집 엔진 받기", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            when (val s = state) {
                EngineStore.State.Missing -> {
                    Caption("문서를 열고 고치는 엔진(약 180MB)을 처음 한 번 받습니다. 그다음부터는 인터넷 없이 동작하고, 앱 업데이트 때 다시 받지 않습니다.")
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = EngineStore::download) { Text("지금 받기") }
                }
                is EngineStore.State.Downloading -> {
                    Caption("받는 중… ${s.percent}%  (${formatSize(s.done)} / ${formatSize(s.total)})")
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(progress = { s.percent / 100f }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(6.dp))
                    Caption("다른 앱으로 넘어가도 계속 받습니다. 끊기면 이어서 받습니다.")
                }
                EngineStore.State.Verifying -> {
                    Caption("받은 파일을 확인하는 중…")
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                is EngineStore.State.Failed -> {
                    Caption(s.message)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = EngineStore::download) { Text("다시 시도") }
                }
                EngineStore.State.Ready -> Unit
            }
        }
    }
}

@Composable
private fun Caption(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
}
