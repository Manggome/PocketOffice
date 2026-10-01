package kr.neptune.pocketoffice.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kr.neptune.pocketoffice.core.DeviceDoc
import kr.neptune.pocketoffice.core.DocFormat
import kr.neptune.pocketoffice.core.DocKind
import kr.neptune.pocketoffice.core.RecentDoc
import kr.neptune.pocketoffice.core.Snapshot
import kr.neptune.pocketoffice.core.SortMode
import kr.neptune.pocketoffice.core.OpenMode
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow

/** 첫 화면에서 일어나는 일 중 액티비티가 처리해야 하는 것 */
interface HomeActions {
    fun openPicker()
    fun openRecent(doc: RecentDoc, mode: OpenMode)
    fun openDevice(doc: DeviceDoc, mode: OpenMode)
    fun createNew(format: DocFormat)
    fun restore(snapshot: Snapshot)
    fun requestAccess()
    fun openSettings()
}

@Composable
fun HomeScreen(vm: HomeViewModel, actions: HomeActions) {
    val tab by vm.tab.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val recent by vm.recentShown.collectAsStateWithLifecycle()
    val device by vm.deviceShown.collectAsStateWithLifecycle()
    val hasAccess by vm.hasAccess.collectAsStateWithLifecycle()
    val scanning by vm.scanning.collectAsStateWithLifecycle()
    val snapshots by vm.recovery.items.collectAsStateWithLifecycle()
    val settings by vm.prefs.settings.collectAsStateWithLifecycle()
    var searching by remember { mutableStateOf(false) }

    Scaffold(
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = actions::openPicker,
                icon = { Icon(Icons.Outlined.FolderOpen, contentDescription = null) },
                text = { Text("파일 열기") },
            )
        },
    ) { padding ->
        // 접었을 때는 한 줄, 펴면 두 줄
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 340.dp),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding() + 96.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            full {
                Header(
                    searching = searching,
                    query = query,
                    onQuery = { vm.query.value = it },
                    onSearch = {
                        searching = !searching
                        if (!searching) vm.query.value = ""
                    },
                    onSettings = actions::openSettings,
                )
            }
            if (!searching) {
                full {
                    ModeRow(settings.openMode) { m -> vm.prefs.update { it.copy(openMode = m) } }
                }
                full { EngineCard(Modifier.padding(vertical = 8.dp)) }
                full { NewRow(actions) }
            }
            if (snapshots.isNotEmpty() && !searching) {
                full { SectionTitle("저장하지 않고 닫은 문서") }
                items(snapshots, key = { "snap-" + it.key }) { snap ->
                    SnapshotRow(snap, onRestore = { actions.restore(snap) }, onDiscard = { vm.recovery.delete(snap.key) })
                }
            }
            full {
                Column {
                    TabRow(selectedTabIndex = tab.ordinal, modifier = Modifier.padding(top = 8.dp)) {
                        HomeTab.entries.forEach { t ->
                            Tab(selected = tab == t, onClick = {
                                vm.tab.value = t
                                if (t == HomeTab.DEVICE) vm.onResume()
                            }, text = { Text(t.label) })
                        }
                    }
                    FilterRow(filter, onFilter = { vm.filter.value = it })
                }
            }

            when (tab) {
                HomeTab.RECENT -> {
                    if (recent.isEmpty()) {
                        full {
                            EmptyNote(
                                if (query.isNotBlank() || filter != null) "찾는 문서가 없습니다"
                                else "아직 연 문서가 없습니다.\n아래 '파일 열기' 나 '내 폰' 탭에서 문서를 골라 보세요."
                            )
                        }
                    }
                    items(recent, key = { "r-" + it.uri }) { doc -> RecentRow(vm, doc, settings.openMode, actions) }
                }

                HomeTab.DEVICE -> {
                    if (!hasAccess) {
                        full { AccessCard(actions::requestAccess) }
                    } else {
                        full {
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    if (scanning) "찾는 중…" else "문서 ${device.size}개",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = {
                                    vm.prefs.update {
                                        it.copy(sort = if (it.sort == SortMode.RECENT) SortMode.NAME else SortMode.RECENT)
                                    }
                                }) { Text(settings.sort.label) }
                                if (scanning) {
                                    CircularProgressIndicator(Modifier.size(20.dp).padding(2.dp), strokeWidth = 2.dp)
                                } else {
                                    IconButton(onClick = vm::rescan) { Icon(Icons.Outlined.Refresh, contentDescription = "새로 고침") }
                                }
                            }
                        }
                        if (device.isEmpty() && !scanning) full { EmptyNote("문서를 찾지 못했습니다") }
                        items(device, key = { "d-" + it.path }) { doc ->
                            var menu by remember { mutableStateOf(false) }
                            DocRow(
                                kind = doc.format?.kind,
                                name = doc.name,
                                detail = listOf(doc.folder, formatSize(doc.size), formatWhen(doc.modified))
                                    .filter { it.isNotEmpty() }.joinToString(" · "),
                                onClick = { actions.openDevice(doc, settings.openMode) },
                                trailing = {
                                    Box {
                                        IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = "더 보기") }
                                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                            val other = settings.openMode.other()
                                            DropdownMenuItem(
                                                text = { Text("${other.label}로 열기") },
                                                onClick = { menu = false; actions.openDevice(doc, other) },
                                            )
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

fun OpenMode.other(): OpenMode = if (this == OpenMode.VIEW) OpenMode.EDIT else OpenMode.VIEW

/** 문서를 누르면 보기로 열지, 편집으로 열지 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModeRow(mode: OpenMode, onChange: (OpenMode) -> Unit) {
    Column(Modifier.padding(top = 4.dp, bottom = 4.dp)) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            OpenMode.entries.forEachIndexed { i, m ->
                SegmentedButton(
                    selected = mode == m,
                    onClick = { onChange(m) },
                    shape = SegmentedButtonDefaults.itemShape(index = i, count = OpenMode.entries.size),
                    icon = {
                        Icon(
                            if (m == OpenMode.VIEW) Icons.Outlined.Visibility else Icons.Outlined.Edit,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                ) { Text(if (m == OpenMode.VIEW) "보기 모드" else "편집 모드") }
            }
        }
        Text(
            if (mode == OpenMode.VIEW) "문서를 누르면 툴바 없이 깔끔하게 봅니다. 고치려면 위쪽 '편집'."
            else "문서를 누르면 바로 고칠 수 있게 엽니다.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp, start = 4.dp),
        )
    }
}

private fun LazyGridScope.full(content: @Composable () -> Unit) {
    item(span = { GridItemSpan(maxLineSpan) }) { content() }
}

@Composable
private fun Header(
    searching: Boolean,
    query: String,
    onQuery: (String) -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (searching) {
            val focus = remember { FocusRequester() }
            OutlinedTextField(
                value = query,
                onValueChange = onQuery,
                placeholder = { Text("파일 이름으로 찾기") },
                singleLine = true,
                modifier = Modifier.weight(1f).focusRequester(focus),
            )
            LaunchedEffect(Unit) { focus.requestFocus() }
            IconButton(onClick = onSearch) { Icon(Icons.Outlined.Close, contentDescription = "검색 닫기") }
        } else {
            Text(
                "포켓오피스",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onSearch) { Icon(Icons.Outlined.Search, contentDescription = "검색") }
            IconButton(onClick = onSettings) { Icon(Icons.Outlined.Settings, contentDescription = "설정") }
        }
    }
}

@Composable
private fun NewRow(actions: HomeActions) {
    Column(Modifier.padding(vertical = 8.dp)) {
        SectionTitle("새로 만들기")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(DocFormat.DOCX to "문서", DocFormat.XLSX to "스프레드시트", DocFormat.PPTX to "프레젠테이션").forEach { (fmt, label) ->
                Card(
                    onClick = { actions.createNew(fmt) },
                    modifier = Modifier.weight(1f).height(92.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = fmt.kind.color().copy(alpha = 0.10f)),
                ) {
                    Column(
                        Modifier.fillMaxSize().padding(10.dp),
                        verticalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Icon(fmt.kind.icon(), contentDescription = null, tint = fmt.kind.color(), modifier = Modifier.size(28.dp))
                        Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterRow(filter: DocKind?, onFilter: (DocKind?) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(selected = filter == null, onClick = { onFilter(null) }, label = { Text("전체") })
        DocKind.entries.forEach { kind ->
            FilterChip(
                selected = filter == kind,
                onClick = { onFilter(if (filter == kind) null else kind) },
                label = { Text(kind.label) },
                leadingIcon = { Icon(kind.icon(), contentDescription = null, tint = kind.color(), modifier = Modifier.size(18.dp)) },
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
    )
}

@Composable
private fun EmptyNote(text: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AccessCard(onAllow: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(vertical = 12.dp), shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(18.dp)) {
            Text("폰에 있는 문서를 한눈에", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(
                "다운로드, 카카오톡 받은 파일, 문서 폴더의 워드·엑셀·파워포인트·PDF 를 모두 모아 보여 줍니다. " +
                    "'모든 파일 접근' 을 허용해야 합니다. 허용하지 않아도 '파일 열기' 로 하나씩 열 수 있습니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = onAllow) { Text("허용하러 가기") }
        }
    }
}

@Composable
private fun SnapshotRow(snap: Snapshot, onRestore: () -> Unit, onDiscard: () -> Unit) {
    DocRow(
        kind = DocFormat.fromName(snap.name)?.kind,
        name = snap.name,
        detail = formatWhen(snap.savedAt) + " · 저장 안 됨",
        onClick = onRestore,
        trailing = {
            IconButton(onClick = onRestore) { Icon(Icons.Outlined.Restore, contentDescription = "이어서 편집") }
            IconButton(onClick = onDiscard) { Icon(Icons.Outlined.Close, contentDescription = "버리기") }
        },
    )
}

@Composable
private fun RecentRow(vm: HomeViewModel, doc: RecentDoc, mode: OpenMode, actions: HomeActions) {
    var menu by remember { mutableStateOf(false) }
    DocRow(
        kind = doc.format?.kind,
        name = doc.name,
        detail = listOf(formatWhen(doc.lastOpened), formatSize(doc.size)).filter { it.isNotEmpty() }.joinToString(" · "),
        onClick = { actions.openRecent(doc, mode) },
        trailing = {
            IconButton(onClick = { vm.recent.setStarred(doc.uri, !doc.starred) }) {
                Icon(
                    if (doc.starred) Icons.Outlined.Star else Icons.Outlined.StarBorder,
                    contentDescription = if (doc.starred) "즐겨찾기 해제" else "즐겨찾기",
                    tint = if (doc.starred) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = "더 보기") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    val other = mode.other()
                    DropdownMenuItem(
                        text = { Text("${other.label}로 열기") },
                        onClick = {
                            menu = false
                            actions.openRecent(doc, other)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("목록에서 빼기") },
                        onClick = {
                            menu = false
                            vm.recent.remove(doc.uri)
                        },
                    )
                }
            }
        },
    )
}

@Composable
private fun DocRow(
    kind: DocKind?,
    name: String,
    detail: String,
    onClick: () -> Unit,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DocBadge(kind)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (detail.isNotEmpty()) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing()
    }
}
