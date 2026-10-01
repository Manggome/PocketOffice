package kr.neptune.pocketoffice.viewer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kr.neptune.pocketoffice.core.DocKind
import kr.neptune.pocketoffice.ui.DocBadge
import kotlin.math.roundToInt

private const val MIN_ZOOM = 1f
private const val MAX_ZOOM = 5f

/** 뷰어 화면이 바깥(액티비티)에 맡기는 일 */
interface PdfViewerActions {
    fun back()
    fun edit()
    fun share()
    fun print()
    fun setNight(on: Boolean)
}

@Composable
fun PdfViewerScreen(
    title: String,
    doc: PdfDoc?,
    error: String?,
    night: Boolean,
    actions: PdfViewerActions,
) {
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val background = if (dark || night) Color(0xFF202124) else Color(0xFFE8EAED)

    Box(Modifier.fillMaxSize().background(background)) {
        when {
            error != null -> ErrorCover(error, actions)
            doc == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            else -> Pages(title, doc, night, actions)
        }
    }
}

@Composable
private fun Pages(title: String, doc: PdfDoc, night: Boolean, actions: PdfViewerActions) {
    val list = rememberLazyListState()
    val hScroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    /** 쪽 너비 배율. 손을 뗄 때만 바뀐다 (그때 다시 그린다) */
    var zoom by remember { mutableFloatStateOf(1f) }
    /** 두 손가락으로 벌리는 동안의 배율. 그림만 늘리고 다시 그리지 않아 부드럽다 */
    var pinch by remember { mutableFloatStateOf(1f) }
    var pinchCenter by remember { mutableStateOf(Offset.Zero) }

    var barsVisible by remember { mutableStateOf(true) }
    var goTo by remember { mutableStateOf(false) }

    // 스크롤을 시작하면 위쪽 막대를 숨긴다 (삼성 기본 뷰어처럼). 다시 보려면 한 번 누른다
    LaunchedEffect(list.isScrollInProgress) {
        if (list.isScrollInProgress) barsVisible = false
    }

    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val viewportW = constraints.maxWidth
        val viewportH = constraints.maxHeight
        val margin = with(density) { 6.dp.roundToPx() }
        val contentW = (viewportW * zoom).roundToInt()
        val pageW = contentW - margin * 2

        /** [focus] 를 중심으로 배율을 [target] 으로 바꾸고, 그 자리가 손가락 아래에 그대로 있게 스크롤을 맞춘다 */
        fun applyZoom(target: Float, focus: Offset) {
            val newZoom = target.coerceIn(MIN_ZOOM, MAX_ZOOM)
            val ratio = newZoom / zoom
            if (ratio == 1f) return
            val item = list.layoutInfo.visibleItemsInfo.firstOrNull { focus.y >= it.offset && focus.y < it.offset + it.size }
                ?: list.layoutInfo.visibleItemsInfo.firstOrNull()
            val anchorIndex = item?.index ?: 0
            val within = item?.let { (focus.y - it.offset) / it.size.coerceAtLeast(1) } ?: 0f
            val anchorNewSize = (item?.size ?: 0) * ratio
            val newH = ((hScroll.value + focus.x) * ratio - focus.x).roundToInt()
            zoom = newZoom
            scope.launch {
                // 새 크기로 배치가 끝난 뒤에 맞춰야 한다. 첫 프레임에 다시 그리고, 다음 프레임에 맞춘다
                withFrameNanos { }
                withFrameNanos { }
                list.scrollToItem(anchorIndex, (within * anchorNewSize - focus.y).roundToInt())
                hScroll.scrollTo(newH.coerceIn(0, hScroll.maxValue))
            }
        }

        Box(
            Modifier
                .fillMaxSize()
                // 두 손가락: 안쪽 목록보다 먼저 받아서 확대만 한다. 한 손가락은 그대로 흘려보내 스크롤된다
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        var pinching = false
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.changes.count { it.pressed } >= 2) {
                                pinching = true
                                pinch = (pinch * event.calculateZoom()).coerceIn(MIN_ZOOM / zoom, MAX_ZOOM / zoom)
                                pinchCenter = event.calculateCentroid(useCurrent = true)
                                event.changes.forEach { it.consume() }
                            }
                        } while (event.changes.any { it.pressed })
                        if (pinching) {
                            val target = zoom * pinch
                            val center = pinchCenter
                            pinch = 1f
                            applyZoom(target, center)
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { barsVisible = !barsVisible },
                        onDoubleTap = { p -> applyZoom(if (zoom > 1.3f) 1f else 2.5f, p) },
                    )
                }
                .graphicsLayer {
                    scaleX = pinch
                    scaleY = pinch
                    transformOrigin = TransformOrigin(
                        (pinchCenter.x / viewportW.coerceAtLeast(1)).coerceIn(0f, 1f),
                        (pinchCenter.y / viewportH.coerceAtLeast(1)).coerceIn(0f, 1f),
                    )
                }
                .horizontalScroll(hScroll, enabled = zoom > 1f),
        ) {
            LazyColumn(
                state = list,
                modifier = Modifier
                    .width(with(density) { contentW.toDp() })
                    .fillMaxHeight(),
                contentPadding = PaddingValues(top = statusTop + 8.dp, bottom = navBottom + 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(doc.pageCount, key = { it }) { index ->
                    PdfPage(doc, index, pageW, night, Modifier.padding(horizontal = with(density) { margin.toDp() }))
                }
            }
        }

        PageBubble(list, doc.pageCount, onClick = { goTo = true }, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = navBottom + 16.dp))

        FastScroller(
            list = list,
            count = doc.pageCount,
            visible = barsVisible || list.isScrollInProgress,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(top = statusTop + 64.dp, bottom = navBottom + 64.dp),
        )

        AnimatedVisibility(
            visible = barsVisible,
            enter = fadeIn() + slideInVertically { -it },
            exit = fadeOut() + slideOutVertically { -it },
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            TopBar(title, night, actions, onGoTo = { goTo = true })
        }
    }

    if (goTo) {
        GoToDialog(doc.pageCount, onDismiss = { goTo = false }) { page ->
            goTo = false
            scope.launch { list.scrollToItem(page) }
        }
    }
}

/** 한 쪽. 새로 그리는 동안에는 이전에 그려 둔 것(흐려도)을 그대로 보여 준다 */
@Composable
private fun PdfPage(doc: PdfDoc, index: Int, width: Int, night: Boolean, modifier: Modifier) {
    var bitmap by remember(index) { mutableStateOf(doc.cached(index, width)) }
    LaunchedEffect(index, width) {
        // 빨리 넘기는 중에 지나가는 쪽까지 그리지 않게 아주 잠깐 기다린다
        if (bitmap == null) delay(40)
        doc.render(index, width)?.let { bitmap = it }
    }
    Box(
        modifier
            .fillMaxWidth()
            .aspectRatio(1f / doc.aspect(index))
            .background(if (night) Color.Black else Color.White),
    ) {
        bitmap?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = "${index + 1}쪽",
                contentScale = ContentScale.FillBounds,
                colorFilter = if (night) InvertFilter else null,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** 흰 종이를 검게, 검은 글씨를 희게 */
private val InvertFilter = ColorFilter.colorMatrix(
    ColorMatrix(
        floatArrayOf(
            -1f, 0f, 0f, 0f, 255f,
            0f, -1f, 0f, 0f, 255f,
            0f, 0f, -1f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f,
        )
    )
)

private fun LazyListState.currentPage(): Int {
    val info = layoutInfo
    val center = (info.viewportStartOffset + info.viewportEndOffset) / 2
    return info.visibleItemsInfo.firstOrNull { center >= it.offset && center < it.offset + it.size }?.index
        ?: firstVisibleItemIndex
}

@Composable
private fun PageBubble(list: LazyListState, count: Int, onClick: () -> Unit, modifier: Modifier) {
    val page by remember { derivedStateOf { list.currentPage() } }
    var show by remember { mutableStateOf(true) }
    LaunchedEffect(page, list.isScrollInProgress) {
        show = true
        if (!list.isScrollInProgress) {
            delay(1500)
            show = false
        }
    }
    AnimatedVisibility(visible = show, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        Surface(
            onClick = onClick,
            shape = RoundedCornerShape(50),
            color = Color(0xCC303134),
            contentColor = Color.White,
        ) {
            Text(
                "${page + 1} / $count",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }
    }
}

/** 오른쪽 가장자리의 손잡이. 끌면 수백 쪽도 금방 넘어간다 */
@Composable
private fun FastScroller(list: LazyListState, count: Int, visible: Boolean, modifier: Modifier) {
    if (count < 4) return
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    var dragging by remember { mutableStateOf(false) }
    var trackH by remember { mutableStateOf(1) }
    val fraction by remember { derivedStateOf { list.currentPage().toFloat() / (count - 1).coerceAtLeast(1) } }
    val thumbH = with(density) { 44.dp.roundToPx() }

    AnimatedVisibility(visible = visible || dragging, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        Box(
            Modifier
                .width(28.dp)
                .fillMaxHeight()
                .padding(end = 4.dp)
                .onSizeChanged { trackH = it.height }
                .pointerInput(count) {
                    detectVerticalDragGestures(
                        onDragStart = { dragging = true },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false },
                    ) { change, _ ->
                        change.consume()
                        val f = ((change.position.y - thumbH / 2f) / (size.height - thumbH).coerceAtLeast(1)).coerceIn(0f, 1f)
                        scope.launch { list.scrollToItem((f * (count - 1)).roundToInt()) }
                    }
                },
        ) {
            val y = ((trackH - thumbH) * fraction).roundToInt()
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset { IntOffset(0, y) }
                    .width(6.dp)
                    .height(44.dp)
                    .background(Color(0x99808080), RoundedCornerShape(3.dp)),
            )
        }
    }
}

@Composable
private fun TopBar(title: String, night: Boolean, actions: PdfViewerActions, onGoTo: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f), shadowElevation = 2.dp) {
        Row(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .height(52.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = actions::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로") }
            DocBadge(DocKind.PDF, size = 28.dp)
            Spacer(Modifier.width(10.dp))
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            FilledTonalButton(onClick = actions::edit, modifier = Modifier.padding(end = 4.dp)) {
                Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("편집")
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = "더 보기") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("쪽으로 이동") }, onClick = { menu = false; onGoTo() })
                    DropdownMenuItem(text = { Text(if (night) "밝은 페이지" else "어두운 페이지") }, onClick = { menu = false; actions.setNight(!night) })
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("공유") }, onClick = { menu = false; actions.share() })
                    DropdownMenuItem(text = { Text("인쇄") }, onClick = { menu = false; actions.print() })
                }
            }
        }
    }
}

@Composable
private fun GoToDialog(count: Int, onDismiss: () -> Unit, onGo: (Int) -> Unit) {
    var text by remember { mutableStateOf("") }
    val page = text.toIntOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("쪽으로 이동") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { v -> text = v.filter { it.isDigit() }.take(6) },
                label = { Text("1 – $count") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        },
        confirmButton = {
            TextButton(enabled = page != null && page in 1..count, onClick = { onGo(page!! - 1) }) { Text("이동") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

@Composable
private fun ErrorCover(message: String, actions: PdfViewerActions) {
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
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = actions::back) { Text("닫기") }
                // 암호 PDF 등은 편집기(OnlyOffice)가 열 수 있다
                Button(onClick = actions::edit) { Text("편집기로 열기") }
            }
        }
    }
}
