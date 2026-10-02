package kr.neptune.pocketoffice.viewer

import android.graphics.Paint
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.BorderColor
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Draw
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.Slideshow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kr.neptune.pocketoffice.ui.PageCommand
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kr.neptune.pocketoffice.core.DocKind
import kr.neptune.pocketoffice.ui.DocBadge
import kotlin.math.hypot
import kotlin.math.roundToInt

private const val MIN_ZOOM = 1f
private const val MAX_ZOOM = 5f

/** 뷰어 화면이 바깥(액티비티)에 맡기는 일 */
interface PdfViewerActions {
    fun back()
    /** OnlyOffice 편집기로 (양식 채우기, 원래 글자 고치기 등) */
    fun openAdvancedEditor()
    fun share()
    fun print()
    fun setNight(on: Boolean)
    fun startInk()
    fun cancelInk()
    fun saveInk()
    /** [page] 부터 발표 */
    fun startPresent(page: Int)
    fun endPresent(lastPage: Int)
}

@Composable
fun PdfViewerScreen(
    title: String,
    doc: PdfDoc?,
    error: String?,
    night: Boolean,
    ink: InkState,
    inking: Boolean,
    saving: Boolean,
    actions: PdfViewerActions,
    /** 발표 중이면 시작 쪽, 아니면 null */
    presentFrom: Int? = null,
    /** 발표를 마치고 돌아올 쪽 (한 번 맞추면 [onResumed]) */
    resumeTo: Int? = null,
    onResumed: () -> Unit = {},
    readCommands: Flow<PageCommand> = emptyFlow(),
    presentCommands: Flow<PageCommand> = emptyFlow(),
) {
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val background = if ((dark || night) && !inking) Color(0xFF202124) else Color(0xFFE8EAED)

    Box(Modifier.fillMaxSize().background(background)) {
        when {
            error != null -> ErrorCover(error, actions)
            doc == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            // 발표 중에도 목록은 그대로 둔다. 끝내고 돌아오면 보던 자리가 남아 있게
            else -> Pages(title, doc, night && !inking, ink, inking, actions, resumeTo, onResumed, readCommands)
        }
        if (doc != null && presentFrom != null) {
            PdfPresentation(doc, presentFrom, presentCommands, onExit = actions::endPresent)
        }
        if (saving) {
            Box(
                Modifier.fillMaxSize().background(Color(0x66000000)).clickable(enabled = true, onClick = {}),
                contentAlignment = Alignment.Center,
            ) {
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.onSurface) {
                    Row(Modifier.padding(horizontal = 24.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp)
                        Spacer(Modifier.width(14.dp))
                        Text("필기를 저장하는 중…")
                    }
                }
            }
        }
    }
    ink.textDialog?.let { TextNoteDialog(ink, it) }
}

@Composable
private fun Pages(
    title: String,
    doc: PdfDoc,
    night: Boolean,
    ink: InkState,
    inking: Boolean,
    actions: PdfViewerActions,
    resumeTo: Int?,
    onResumed: () -> Unit,
    commands: Flow<PageCommand>,
) {
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
        if (list.isScrollInProgress && !inking) barsVisible = false
    }

    // 발표에서 돌아오면 마지막으로 보여 준 쪽으로
    LaunchedEffect(resumeTo) {
        if (resumeTo != null) {
            list.scrollToItem(resumeTo)
            onResumed()
        }
    }

    // 키보드·블루투스 리모컨으로 한 쪽씩
    LaunchedEffect(commands) {
        commands.collect { cmd ->
            val cur = list.currentPage()
            when (cmd) {
                PageCommand.NEXT -> list.animateScrollToItem((cur + 1).coerceAtMost(doc.pageCount - 1))
                PageCommand.PREV -> list.animateScrollToItem((cur - 1).coerceAtLeast(0))
                PageCommand.START -> actions.startPresent(cur)
                else -> Unit
            }
        }
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
                // 두 손가락: 안쪽보다 먼저 받아서 확대·이동만 한다 (필기 중이어도). 한 손가락은 그대로 흘려보낸다
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
                                val pan = event.calculatePan()
                                list.dispatchRawDelta(-pan.y)
                                hScroll.dispatchRawDelta(-pan.x)
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
                .then(
                    if (inking) Modifier
                    else Modifier.pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { barsVisible = !barsVisible },
                            onDoubleTap = { p -> applyZoom(if (zoom > 1.3f) 1f else 2.5f, p) },
                        )
                    }
                )
                .graphicsLayer {
                    scaleX = pinch
                    scaleY = pinch
                    transformOrigin = TransformOrigin(
                        (pinchCenter.x / viewportW.coerceAtLeast(1)).coerceIn(0f, 1f),
                        (pinchCenter.y / viewportH.coerceAtLeast(1)).coerceIn(0f, 1f),
                    )
                }
                .horizontalScroll(hScroll, enabled = zoom > 1f && !inking),
        ) {
            LazyColumn(
                state = list,
                // 필기 중에는 한 손가락이 펜이다. 넘기기는 두 손가락으로
                userScrollEnabled = !inking,
                modifier = Modifier
                    .width(with(density) { contentW.toDp() })
                    .fillMaxHeight(),
                contentPadding = PaddingValues(
                    top = statusTop + if (inking) 60.dp else 8.dp,
                    bottom = navBottom + if (inking) 150.dp else 24.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(doc.pageCount, key = { it }) { index ->
                    PdfPage(doc, index, pageW, night, ink, inking, Modifier.padding(horizontal = with(density) { margin.toDp() }))
                }
            }
        }

        if (!inking) {
            PageBubble(list, doc.pageCount, onClick = { goTo = true }, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = navBottom + 16.dp))
            FastScroller(
                list = list,
                count = doc.pageCount,
                visible = barsVisible || list.isScrollInProgress,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(top = statusTop + 64.dp, bottom = navBottom + 64.dp),
            )
        }

        if (inking) {
            Box(Modifier.align(Alignment.TopCenter)) { InkTopBar(ink, actions) }
            Box(Modifier.align(Alignment.BottomCenter)) { InkToolbar(ink) }
        } else {
            AnimatedVisibility(
                visible = barsVisible,
                enter = fadeIn() + slideInVertically { -it },
                exit = fadeOut() + slideOutVertically { -it },
                modifier = Modifier.align(Alignment.TopCenter),
            ) {
                TopBar(title, night, actions, onGoTo = { goTo = true }, onPresent = { actions.startPresent(list.currentPage()) })
            }
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
private fun PdfPage(doc: PdfDoc, index: Int, width: Int, night: Boolean, ink: InkState, inking: Boolean, modifier: Modifier) {
    var bitmap by remember(index) { mutableStateOf(doc.cached(index, width)) }
    // 필기를 저장하면 새 문서로 바뀐다 → 다시 그린다
    LaunchedEffect(doc, index, width) {
        // 빨리 넘기는 중에 지나가는 쪽까지 그리지 않게 아주 잠깐 기다린다
        if (bitmap == null) delay(40)
        doc.render(index, width)?.let { bitmap = it }
    }
    val aspect = doc.aspect(index)
    Box(
        modifier
            .fillMaxWidth()
            .aspectRatio(1f / aspect)
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
        InkLayer(ink, index, aspect, inking)
    }
}

// ---------------------------------------------------------------------- 필기

/** 한 쪽 위의 필기. 필기 모드일 때만 손가락을 받는다 */
@Composable
private fun InkLayer(ink: InkState, page: Int, aspect: Float, enabled: Boolean) {
    val context = LocalContext.current
    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = InkFont.get(context) } }
    val marks = ink.marks.filter { it.page == page }
    val live = ink.live?.takeIf { it.page == page }
    if (marks.isEmpty() && live == null && !enabled) return

    Canvas(
        Modifier
            .fillMaxSize()
            .then(if (enabled) Modifier.pointerInput(page, ink.tool) { inkGestures(ink, page, aspect, paint) } else Modifier)
    ) {
        marks.forEach { drawMark(it, paint) }
        live?.let { drawMark(it, paint) }
    }
}

// 빠른 펜 움직임 사이의 점(historical)까지 받아야 선이 각지지 않는다
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.inkGestures(
    ink: InkState,
    page: Int,
    aspect: Float,
    paint: Paint,
) {
    fun norm(o: Offset) = Offset(
        (o.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f),
        (o.y / size.height.coerceAtLeast(1)).coerceIn(0f, 1f),
    )
    awaitEachGesture {
        val down = awaitFirstDown()
        when (ink.tool) {
            InkTool.TEXT -> {
                val up = waitForUpOrCancellation() ?: return@awaitEachGesture
                up.consume()
                val p = norm(up.position)
                val hit = ink.marks.filterIsInstance<TextNote>()
                    .lastOrNull { it.page == page && ink.textBounds(it, aspect, paint).contains(p) }
                ink.textDialog = InkState.TextDialog(page, hit?.x ?: p.x, hit?.y ?: p.y, hit)
            }

            InkTool.ERASER -> {
                down.consume()
                fun eraseAt(p: Offset) = ink.remove(ink.hitTest(page, p, InkState.ERASER_RADIUS, aspect, paint))
                eraseAt(norm(down.position))
                while (true) {
                    val ev = awaitPointerEvent()
                    // 두 번째 손가락이 닿으면 확대·이동으로 넘긴다
                    if (ev.changes.count { it.pressed } > 1 || ev.changes.any { it.isConsumed }) break
                    val c = ev.changes.first()
                    if (!c.pressed) break
                    c.historical.forEach { eraseAt(norm(it.position)) }
                    eraseAt(norm(c.position))
                    c.consume()
                }
            }

            InkTool.PEN, InkTool.HIGHLIGHTER -> {
                down.consume()
                val highlighter = ink.tool == InkTool.HIGHLIGHTER
                val points = ArrayList<Offset>().apply { add(norm(down.position)) }
                fun live() = Stroke(
                    page = page,
                    points = points.toList(),
                    color = if (highlighter) ink.highlighterColor else ink.penColor,
                    width = if (highlighter) InkState.HIGHLIGHTER_WIDTH else ink.penWidth,
                    highlighter = highlighter,
                )
                ink.live = live()
                fun add(o: Offset) {
                    val p = norm(o)
                    val last = points.last()
                    // 거의 같은 자리는 건너뛴다 (파일이 쓸데없이 커지지 않게)
                    if (hypot(p.x - last.x, (p.y - last.y) * aspect) > 0.0012f) points += p
                }
                var cancelled = false
                while (true) {
                    val ev = awaitPointerEvent()
                    if (ev.changes.count { it.pressed } > 1 || ev.changes.any { it.isConsumed }) {
                        cancelled = true
                        break
                    }
                    val c = ev.changes.first()
                    c.historical.forEach { add(it.position) }
                    add(c.position)
                    c.consume()
                    if (!c.pressed) break
                    ink.live = live()
                }
                val stroke = live()
                ink.live = null
                if (!cancelled) ink.add(stroke)
            }
        }
    }
}

private fun DrawScope.drawMark(m: Mark, paint: Paint) {
    val w = size.width
    val h = size.height
    when (m) {
        is Stroke -> {
            if (m.points.isEmpty()) return
            val path = Path()
            path.moveTo(m.points[0].x * w, m.points[0].y * h)
            if (m.points.size == 1) path.lineTo(m.points[0].x * w + 0.5f, m.points[0].y * h)
            for (i in 1 until m.points.size) path.lineTo(m.points[i].x * w, m.points[i].y * h)
            drawPath(
                path,
                color = Color(m.color).copy(alpha = if (m.highlighter) PdfAnnotator.HIGHLIGHTER_ALPHA else 1f),
                style = androidx.compose.ui.graphics.drawscope.Stroke(
                    width = m.width * w,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                ),
                blendMode = if (m.highlighter) BlendMode.Multiply else BlendMode.SrcOver,
            )
        }

        is TextNote -> {
            paint.color = m.color
            paint.textSize = m.size * w
            drawIntoCanvas { canvas ->
                m.lines.forEachIndexed { i, line ->
                    val baseline = m.y * h + (i * TEXT_LINE_HEIGHT + TEXT_ASCENT) * m.size * w
                    canvas.nativeCanvas.drawText(line, m.x * w, baseline, paint)
                }
            }
        }
    }
}

@Composable
private fun InkTopBar(ink: InkState, actions: PdfViewerActions) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shadowElevation = 3.dp,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .height(52.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = actions::cancelInk) { Icon(Icons.Outlined.Close, contentDescription = "필기 끝내기") }
            Text("필기", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            IconButton(onClick = ink::undo, enabled = ink.canUndo) { Icon(Icons.AutoMirrored.Outlined.Undo, contentDescription = "실행 취소") }
            IconButton(onClick = ink::redo, enabled = ink.canRedo) { Icon(Icons.AutoMirrored.Outlined.Redo, contentDescription = "다시 실행") }
            FilledTonalButton(onClick = actions::saveInk, enabled = !ink.isEmpty, modifier = Modifier.padding(start = 4.dp, end = 4.dp)) {
                Text("저장")
            }
        }
    }
}

/** 아래 도구 막대: 펜 / 형광펜 / 글자 / 지우개, 그리고 고른 도구의 색과 굵기 */
@Composable
private fun InkToolbar(ink: InkState) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shadowElevation = 6.dp,
        shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                ToolButton(InkTool.PEN, Icons.Outlined.Draw, ink)
                ToolButton(InkTool.HIGHLIGHTER, Icons.Outlined.BorderColor, ink)
                ToolButton(InkTool.TEXT, Icons.Outlined.TextFields, ink)
                ToolButton(InkTool.ERASER, Icons.Outlined.CleaningServices, ink)
            }
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.fillMaxWidth().heightIn(min = 40.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                when (ink.tool) {
                    InkTool.PEN -> {
                        InkState.PEN_COLORS.forEach { c -> Swatch(c, ink.penColor == c) { ink.penColor = c } }
                        Spacer(Modifier.width(14.dp))
                        InkState.PEN_WIDTHS.forEachIndexed { i, w ->
                            WidthDot(i, ink.penWidth == w) { ink.penWidth = w }
                        }
                    }
                    InkTool.HIGHLIGHTER ->
                        InkState.HIGHLIGHTER_COLORS.forEach { c -> Swatch(c, ink.highlighterColor == c) { ink.highlighterColor = c } }
                    InkTool.TEXT -> {
                        InkState.PEN_COLORS.forEach { c -> Swatch(c, ink.textColor == c) { ink.textColor = c } }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "쪽을 눌러 글자 넣기",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    InkTool.ERASER -> Text(
                        "지울 선이나 글자를 문지르세요 · 넘기기는 두 손가락",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ToolButton(tool: InkTool, icon: androidx.compose.ui.graphics.vector.ImageVector, ink: InkState) {
    val selected = ink.tool == tool
    Column(
        Modifier
            .clickable { ink.tool = tool }
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                RoundedCornerShape(12.dp),
            )
            .padding(horizontal = 14.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            icon,
            contentDescription = tool.label,
            tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            tool.label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Swatch(color: Int, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .padding(horizontal = 5.dp)
            .size(30.dp)
            .clickable(onClick = onClick)
            .border(
                BorderStroke(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else Color(0x33000000)),
                CircleShape,
            )
            .padding(4.dp)
            .background(Color(color), CircleShape),
    )
}

@Composable
private fun WidthDot(level: Int, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .padding(horizontal = 4.dp)
            .size(32.dp)
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size((5 + level * 5).dp).background(MaterialTheme.colorScheme.onSurface, CircleShape))
    }
}

/** 글자 넣기 / 고치기. 키보드는 여기서만 뜬다 */
@Composable
private fun TextNoteDialog(ink: InkState, d: InkState.TextDialog) {
    val editing = d.editing
    var text by remember(d) { mutableStateOf(editing?.text ?: "") }
    var size by remember(d) { mutableStateOf(editing?.size ?: ink.textSize) }
    var color by remember(d) { mutableStateOf(editing?.color ?: ink.textColor) }
    val focus = remember { FocusRequester() }

    fun close() {
        ink.textDialog = null
    }

    AlertDialog(
        onDismissRequest = ::close,
        title = { Text(if (editing == null) "글자 넣기" else "글자 고치기") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text("넣을 글자") },
                    minLines = 2,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                LaunchedEffect(Unit) { focus.requestFocus() }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("작게", "보통", "크게").forEachIndexed { i, label ->
                        val s = InkState.TEXT_SIZES[i]
                        FilterChip(selected = size == s, onClick = { size = s }, label = { Text(label) })
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    InkState.PEN_COLORS.forEach { c -> Swatch(c, color == c) { color = c } }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val t = text.trimEnd()
                ink.textSize = size
                ink.textColor = color
                when {
                    editing != null && t.isBlank() -> ink.remove(listOf(editing))
                    editing != null -> ink.replace(editing, editing.copy(text = t, size = size, color = color))
                    t.isNotBlank() -> ink.add(TextNote(d.page, d.x, d.y, t, size, color))
                }
                close()
            }) { Text(if (editing == null) "넣기" else "고치기") }
        },
        dismissButton = {
            Row {
                if (editing != null) {
                    TextButton(onClick = {
                        ink.remove(listOf(editing))
                        close()
                    }) { Text("지우기") }
                }
                TextButton(onClick = ::close) { Text("취소") }
            }
        },
    )
}

// ---------------------------------------------------------------------- 보기

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
private fun TopBar(title: String, night: Boolean, actions: PdfViewerActions, onGoTo: () -> Unit, onPresent: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    // 반투명 색은 테마의 짝 글자색을 못 찾아 검정이 된다. 글자색을 직접 정한다
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        shadowElevation = 2.dp,
    ) {
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
            IconButton(onClick = onPresent) { Icon(Icons.Outlined.Slideshow, contentDescription = "발표") }
            FilledTonalButton(onClick = actions::startInk, modifier = Modifier.padding(end = 4.dp)) {
                Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("필기")
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = "더 보기") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("발표 (이 쪽부터)") }, onClick = { menu = false; onPresent() })
                    DropdownMenuItem(text = { Text("쪽으로 이동") }, onClick = { menu = false; onGoTo() })
                    DropdownMenuItem(text = { Text(if (night) "밝은 페이지" else "어두운 페이지") }, onClick = { menu = false; actions.setNight(!night) })
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("공유") }, onClick = { menu = false; actions.share() })
                    DropdownMenuItem(text = { Text("인쇄") }, onClick = { menu = false; actions.print() })
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("고급 편집기로 열기") },
                        onClick = { menu = false; actions.openAdvancedEditor() },
                    )
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
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.onSurface) {
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
                Button(onClick = actions::openAdvancedEditor) { Text("편집기로 열기") }
            }
        }
    }
}
