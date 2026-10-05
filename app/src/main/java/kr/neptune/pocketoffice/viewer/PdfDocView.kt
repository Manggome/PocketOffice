package kr.neptune.pocketoffice.viewer

import android.graphics.Paint
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.animateTo
import androidx.compose.animation.core.spring
import androidx.compose.animation.splineBasedDecay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * PDF 를 그리는 판. 삼성 PDF 뷰어·갤러리처럼 "배치는 한 번, 확대·이동은 좌표만" 바꾼다.
 *
 * 쪽은 화면 너비에 맞춘 크기(배율 1)로 한 번만 세로로 늘어놓는다(내용 좌표). 화면에는
 *   화면 = 내용 × scale − pan
 * 으로 옮겨 그린다. 두 손가락으로 벌려도, 손을 떼도 배치가 다시 계산되지 않으니 튀거나
 * 번쩍이지 않는다. 손을 뗀 뒤 잠깐 멈추면 보이는 부분만 화면 해상도로 다시 그려(조각) 선명해진다.
 */

private const val MIN_SCALE = 1f
private const val MAX_SCALE = 6f

class DocViewState(val doc: PdfDoc, from: DocViewState? = null) {

    /** 같은 문서를 다시 열었을 때(필기 저장 뒤) 이어받을 배율·위치 */
    private var carry: Triple<Float, Float, Float>? = from?.let { Triple(it.scale, it.panX, it.panY) }

    var viewport by mutableStateOf(IntSize.Zero)
        private set
    var scale by mutableFloatStateOf(1f)
        private set
    var panX by mutableFloatStateOf(0f)
        private set
    var panY by mutableFloatStateOf(0f)
        private set

    /** 손가락이나 관성으로 움직이는 중 (위쪽 막대 숨김, 쪽 번호 표시에 쓴다) */
    var moving by mutableStateOf(false)
        internal set

    /** 위·아래 막대에 가리지 않게 비워 둘 내용 바깥 여백 (px) */
    var topPad = 0f
    var bottomPad = 0f

    internal var margin = 0f
    internal var gap = 0f

    /** 배율 1 에서 쪽의 가로 (px) */
    var baseWidth = 0f
        private set
    private var tops = FloatArray(0)
    private var heights = FloatArray(0)
    var contentHeight = 0f
        private set

    fun pageTop(i: Int) = tops[i]
    fun pageHeight(i: Int) = heights[i]
    /** 배치가 끝났는가. viewport(상태)를 읽어서, 화면 크기가 정해지면 지켜보는 쪽이 다시 깨어난다 */
    val ready: Boolean get() = viewport.width > 0 && baseWidth > 0 && tops.isNotEmpty()

    /** 화면 크기가 바뀌었을 때(접기·펴기·회전): 다시 늘어놓고 보던 쪽을 그대로 맞춘다 */
    fun setViewport(size: IntSize, marginPx: Float, gapPx: Float) {
        if (size == viewport && marginPx == margin) return
        val anchorPage = if (ready) currentPage else 0
        val anchorWithin = if (ready) ((panY + viewport.height / 2f) / scale - tops[anchorPage]) / heights[anchorPage].coerceAtLeast(1f) else 0f
        viewport = size
        margin = marginPx
        gap = gapPx
        baseWidth = (size.width - 2 * marginPx).coerceAtLeast(1f)
        val n = doc.pageCount
        tops = FloatArray(n)
        heights = FloatArray(n)
        var y = 0f
        for (i in 0 until n) {
            tops[i] = y
            heights[i] = baseWidth * doc.aspect(i)
            y += heights[i] + gapPx
        }
        contentHeight = (y - gapPx).coerceAtLeast(0f)
        val c = carry
        if (c != null) {
            carry = null
            scale = c.first
            panX = c.second
            panY = c.third
        } else if (anchorPage in 0 until n) {
            panY = (tops[anchorPage] + anchorWithin * heights[anchorPage]) * scale - size.height / 2f
        }
        clamp()
    }

    // ---------------------------------------------------------------- 좌표

    fun toContent(screen: Offset) = Offset((screen.x + panX) / scale, (screen.y + panY) / scale)

    /** 화면 위 [screen] 이 어느 쪽의 어디(쪽 기준 0..1)인지. 쪽 밖이면 null */
    fun hitPage(screen: Offset): Pair<Int, Offset>? {
        val c = toContent(screen)
        val i = pageAtContentY(c.y)
        if (i < 0) return null
        val x = (c.x - margin) / baseWidth
        val y = (c.y - tops[i]) / heights[i]
        if (x < 0f || x > 1f || y < 0f || y > 1f) return null
        return i to Offset(x, y)
    }

    /** 쪽 [page] 의 비율 좌표를 화면 좌표로 (필기 중 선이 밖으로 나가도 이어지게) */
    fun pageToScreen(page: Int, p: Offset) = Offset(
        (margin + p.x * baseWidth) * scale - panX,
        (tops[page] + p.y * heights[page]) * scale - panY,
    )

    fun screenToPage(page: Int, screen: Offset): Offset {
        val c = toContent(screen)
        return Offset((c.x - margin) / baseWidth, (c.y - tops[page]) / heights[page])
    }

    private fun pageAtContentY(y: Float): Int {
        if (tops.isEmpty()) return -1
        var lo = 0
        var hi = tops.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (tops[mid] <= y) lo = mid else hi = mid - 1
        }
        return if (y <= tops[lo] + heights[lo]) lo else -1
    }

    /** 화면 가운데에 걸친 쪽 */
    val currentPage: Int
        get() {
            if (tops.isEmpty()) return 0
            val y = (panY + viewport.height / 2f) / scale
            var lo = 0
            var hi = tops.size - 1
            while (lo < hi) {
                val mid = (lo + hi + 1) / 2
                if (tops[mid] <= y) lo = mid else hi = mid - 1
            }
            return lo
        }

    /** 화면에 걸친 쪽 범위 */
    fun visiblePages(): IntRange {
        if (tops.isEmpty()) return IntRange.EMPTY
        val top = panY / scale
        val bottom = (panY + viewport.height) / scale
        var first = currentPageAt(top)
        while (first > 0 && tops[first] > top) first--
        var last = first
        while (last + 1 < tops.size && tops[last + 1] < bottom) last++
        return first..last
    }

    private fun currentPageAt(y: Float): Int {
        var lo = 0
        var hi = tops.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (tops[mid] <= y) lo = mid else hi = mid - 1
        }
        return lo
    }

    // ---------------------------------------------------------------- 움직이기

    fun panBy(dx: Float, dy: Float) {
        panX += dx
        panY += dy
        clamp()
    }

    /** [focus](화면 좌표)를 중심으로 [factor] 배 */
    fun zoomBy(factor: Float, focus: Offset) {
        val newScale = (scale * factor).coerceIn(MIN_SCALE, MAX_SCALE)
        val f = newScale / scale
        if (f == 1f) return
        panX = (panX + focus.x) * f - focus.x
        panY = (panY + focus.y) * f - focus.y
        scale = newScale
        clamp()
    }

    fun setTransform(newScale: Float, newPanX: Float, newPanY: Float) {
        scale = newScale.coerceIn(MIN_SCALE, MAX_SCALE)
        panX = newPanX
        panY = newPanY
        clamp()
    }

    fun scrollToPage(page: Int) {
        if (!ready) return
        panY = tops[page.coerceIn(0, tops.size - 1)] * scale - topPad
        clamp()
    }

    /** 다음/이전 쪽의 맨 위로 갈 때의 panY */
    fun panYForPage(page: Int): Float {
        val p = tops[page.coerceIn(0, tops.size - 1)] * scale - topPad
        return p.coerceIn(minPanY(), maxPanY())
    }

    private fun minPanY() = -topPad
    private fun maxPanY() = max(minPanY(), contentHeight * scale + bottomPad - viewport.height)

    private fun clamp() {
        val contentW = viewport.width * scale
        panX = if (contentW <= viewport.width) (contentW - viewport.width) / 2f
        else panX.coerceIn(0f, contentW - viewport.width)
        panY = panY.coerceIn(minPanY(), maxPanY())
    }

    // ---------------------------------------------------------------- 그림

    /** 배율 1 해상도로 그려 둔 쪽들 (보이는 곳 근처만 들고 있는다) */
    internal val pages = mutableStateMapOf<Int, ImageBitmap>()

    /** 확대했을 때 보이는 부분을 화면 해상도로 그린 조각. 내용 좌표의 [rect] 자리에 그린다 */
    internal class Tile(val bitmap: ImageBitmap, val rect: Rect)

    internal val tiles = mutableStateMapOf<Int, Tile>()

    /** 부드럽게 [page] 맨 위로 (PdfDocView 가 붙인다) */
    internal var animateToPage: ((Int) -> Unit)? = null

    fun goToPage(page: Int, animate: Boolean = true) {
        val a = animateToPage
        if (animate && a != null) a(page) else scrollToPage(page)
    }
}

/**
 * PDF 쪽들을 그리고 손가락을 받는다.
 *
 * - 한 손가락: 끌어서 이동, 놓으면 관성으로 미끄러진다 (필기 중이면 펜)
 * - 두 손가락: 벌리고 오므려 확대·축소, 동시에 이동
 * - 두 번 탭: 그 자리를 2.5배로 / 원래대로
 */
@OptIn(kotlinx.coroutines.FlowPreview::class)
@Composable
fun PdfDocView(
    state: DocViewState,
    night: Boolean,
    ink: InkState,
    inking: Boolean,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = InkFont.get(context) } }
    val decay = remember(density) { splineBasedDecay<Float>(density) }
    val gestures = remember(state) { Gestures(state, scope, decay).also { g -> state.animateToPage = g::scrollToPage } }

    // 보이는 쪽 ±2 를 배율 1 해상도로 미리 그려 두고, 멀어진 것은 버린다
    LaunchedEffect(state) {
        snapshotFlow { if (state.ready) state.visiblePages() else IntRange.EMPTY }
            .distinctUntilChanged()
            .collectLatest { range ->
                if (range.isEmpty()) return@collectLatest
                val want = (range.first - 2).coerceAtLeast(0)..(range.last + 2).coerceAtMost(state.doc.pageCount - 1)
                state.pages.keys.filter { it !in want }.forEach { state.pages.remove(it) }
                val width = state.baseWidth.roundToInt()
                // 보이는 쪽부터
                for (i in range.toList() + want.filter { it !in range }) {
                    if (state.pages[i] == null) {
                        state.doc.render(i, width)?.let { state.pages[i] = it.asImageBitmap() }
                    }
                }
            }
    }

    // 멈추면 보이는 부분을 화면 해상도로 (확대했을 때만)
    LaunchedEffect(state) {
        snapshotFlow { Triple(state.scale, state.panX, state.panY) to state.moving }
            .debounce(140)
            .collectLatest { (t, moving) ->
                val (s, px, py) = t
                if (moving || !state.ready) return@collectLatest
                if (s < 1.15f) {
                    state.tiles.clear()
                    return@collectLatest
                }
                val vw = state.viewport.width.toFloat()
                val vh = state.viewport.height.toFloat()
                val range = state.visiblePages()
                state.tiles.keys.filter { it !in range }.forEach { state.tiles.remove(it) }
                for (i in range) {
                    val left = (state.margin) * s - px
                    val top = state.pageTop(i) * s - py
                    val w = state.baseWidth * s
                    val h = state.pageHeight(i) * s
                    val l = max(0f, left)
                    val t0 = max(0f, top)
                    val r = min(vw, left + w)
                    val b = min(vh, top + h)
                    if (r <= l || b <= t0) continue
                    val tileL = floor(l)
                    val tileT = floor(t0)
                    val tw = ceil(r - tileL).toInt()
                    val th = ceil(b - tileT).toInt()
                    val bmp = state.doc.renderTile(i, w, tileL - left, tileT - top, tw, th) ?: continue
                    state.tiles[i] = DocViewState.Tile(
                        bmp.asImageBitmap(),
                        Rect(
                            (tileL + px) / s, (tileT + py) / s,
                            (tileL + tw + px) / s, (tileT + th + py) / s,
                        ),
                    )
                }
            }
    }

    Canvas(
        modifier
            .fillMaxSize()
            .onSizeChanged {
                state.setViewport(it, with(density) { 6.dp.toPx() }, with(density) { 8.dp.toPx() })
            }
            .pointerInput(state, inking, ink.tool) {
                with(gestures) { handle(inking, ink, paint, onTap) }
            },
    ) {
        if (!state.ready) return@Canvas
        val s = state.scale
        val px = state.panX
        val py = state.panY
        val filter = if (night) NightFilter else null
        for (i in state.visiblePages()) {
            val left = state.margin * s - px
            val top = state.pageTop(i) * s - py
            val w = state.baseWidth * s
            val h = state.pageHeight(i) * s
            drawRect(if (night) Color.Black else Color.White, Offset(left, top), androidx.compose.ui.geometry.Size(w, h))
            state.pages[i]?.let { bmp ->
                drawImage(
                    bmp,
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(bmp.width, bmp.height),
                    dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
                    dstSize = IntSize(w.roundToInt(), h.roundToInt()),
                    filterQuality = FilterQuality.Medium,
                    colorFilter = filter,
                )
            }
            state.tiles[i]?.let { tile ->
                val r = tile.rect
                drawImage(
                    tile.bitmap,
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(tile.bitmap.width, tile.bitmap.height),
                    dstOffset = IntOffset((r.left * s - px).roundToInt(), (r.top * s - py).roundToInt()),
                    dstSize = IntSize((r.width * s).roundToInt(), (r.height * s).roundToInt()),
                    filterQuality = FilterQuality.Medium,
                    colorFilter = filter,
                )
            }
            // 필기: 쪽 크기의 판에 옮겨 그린다
            val marks = ink.marks.filter { it.page == i }
            val live = ink.live?.takeIf { it.page == i }
            if (marks.isNotEmpty() || live != null) {
                clipRect(left, top, left + w, top + h) {
                    translate(left, top) {
                        marks.forEach { drawMarkSized(it, paint, w, h) }
                        live?.let { drawMarkSized(it, paint, w, h) }
                    }
                }
            }
        }
    }
}

/** 흰 종이를 검게, 검은 글씨를 희게 */
private val NightFilter = ColorFilter.colorMatrix(
    androidx.compose.ui.graphics.ColorMatrix(
        floatArrayOf(
            -1f, 0f, 0f, 0f, 255f,
            0f, -1f, 0f, 0f, 255f,
            0f, 0f, -1f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f,
        )
    )
)

/** 손가락 처리. 상태(관성 애니메이션, 두 번 탭 판정)를 제스처 사이에 들고 있어야 해서 따로 둔다 */
private class Gestures(
    private val state: DocViewState,
    private val scope: CoroutineScope,
    private val decay: androidx.compose.animation.core.DecayAnimationSpec<Float>,
) {
    private var fling: Job? = null
    private var pendingTap: Job? = null
    private var lastTapTime = 0L
    private var lastTapPos = Offset.Zero

    fun stop() {
        fling?.cancel()
        fling = null
    }

    @OptIn(ExperimentalComposeUiApi::class)
    suspend fun PointerInputScope.handle(inking: Boolean, ink: InkState, paint: Paint, onTap: () -> Unit) {
        val slop = viewConfiguration.touchSlop
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            stop()
            val tracker = VelocityTracker()
            tracker.addPosition(down.uptimeMillis, down.position)
            var moved = false
            var pinched = false
            var total = Offset.Zero

            // 필기 중 한 손가락은 펜 (두 번째 손가락이 닿으면 그 획은 버리고 확대·이동으로)
            val inkSession = if (inking) InkSession.start(state, ink, paint, down.position) else null
            if (inkSession != null) down.consume()

            while (true) {
                val ev = awaitPointerEvent()
                val pressed = ev.changes.filter { it.pressed }
                if (pressed.isEmpty()) {
                    inkSession?.finish(cancelled = pinched)
                    break
                }
                if (pressed.size >= 2) {
                    if (!pinched) {
                        pinched = true
                        state.moving = true
                        inkSession?.cancel()
                    }
                    val zoom = ev.calculateZoom()
                    val pan = ev.calculatePan()
                    val centroid = ev.calculateCentroid(useCurrent = true)
                    state.zoomBy(zoom, centroid)
                    state.panBy(-pan.x, -pan.y)
                    ev.changes.forEach { it.consume() }
                    continue
                }
                val c = pressed.first()
                if (inkSession != null && !pinched) {
                    inkSession.move(c)
                    c.consume()
                    continue
                }
                // 여기부터는 한 손가락 끌기 (필기 중 두 손가락을 떼다 한 손가락이 남은 경우도)
                val d = c.positionChange()
                total += d
                if (!moved && hypot(total.x, total.y) > slop) {
                    moved = true
                    state.moving = true
                }
                if (moved) {
                    state.panBy(-d.x, -d.y)
                    tracker.addPosition(c.uptimeMillis, c.position)
                    c.consume()
                }
            }

            when {
                pinched -> state.moving = false
                moved && !inking -> {
                    val v = tracker.calculateVelocity()
                    flingBy(-v.x, -v.y)
                }
                moved -> state.moving = false
                !inking -> tap(down.position, onTap)
                ink.tool == InkTool.TEXT -> openText(ink, paint, down.position)
            }
        }
    }

    /** 글자 도구로 쪽을 눌렀을 때: 이미 있는 글자 칸이면 고치기, 아니면 그 자리에 넣기 */
    private fun openText(ink: InkState, paint: Paint, at: Offset) {
        val (page, p) = state.hitPage(at) ?: return
        val aspect = state.pageHeight(page) / state.baseWidth
        val hit = ink.marks.filterIsInstance<TextNote>()
            .lastOrNull { it.page == page && ink.textBounds(it, aspect, paint).contains(p) }
        ink.textDialog = InkState.TextDialog(page, hit?.x ?: p.x, hit?.y ?: p.y, hit)
    }

    private fun flingBy(vx: Float, vy: Float) {
        if (abs(vx) < 50f && abs(vy) < 50f) {
            state.moving = false
            return
        }
        fling = scope.launch {
            try {
                var lastX = 0f
                var lastY = 0f
                // 가로·세로를 한 번에: 속도 크기로 미끄러지고 방향을 나눠 준다
                val speed = hypot(vx, vy)
                val ux = vx / speed
                val uy = vy / speed
                AnimationState(0f, speed).animateDecay(decay) {
                    val dist = value
                    val x = dist * ux
                    val y = dist * uy
                    state.panBy(x - lastX, y - lastY)
                    lastX = x
                    lastY = y
                }
            } finally {
                state.moving = false
            }
        }
    }

    private fun tap(pos: Offset, onTap: () -> Unit) {
        val now = System.currentTimeMillis()
        val isDouble = now - lastTapTime < 300 && hypot(pos.x - lastTapPos.x, pos.y - lastTapPos.y) < 120f
        if (isDouble) {
            pendingTap?.cancel()
            lastTapTime = 0L
            animateZoom(if (state.scale > 1.3f) 1f else 2.5f, pos)
            return
        }
        lastTapTime = now
        lastTapPos = pos
        // 두 번 탭인지 기다렸다가 한 번 탭으로 처리
        pendingTap = scope.launch {
            delay(260)
            onTap()
        }
    }

    /** 두 번 탭: [focus] 를 손가락 아래에 둔 채 부드럽게 배율을 바꾼다 */
    private fun animateZoom(target: Float, focus: Offset) {
        val startScale = state.scale
        val content = state.toContent(focus)
        fling = scope.launch {
            state.moving = true
            try {
                Animatable(startScale).animateTo(target, spring(stiffness = 400f)) {
                    val s = value
                    state.setTransform(s, content.x * s - focus.x, content.y * s - focus.y)
                }
            } finally {
                state.moving = false
            }
        }
    }

    /** 다음/이전 쪽으로 부드럽게 (리모컨·키보드) */
    fun scrollToPage(page: Int) {
        stop()
        val from = state.panY
        val to = state.panYForPage(page)
        fling = scope.launch {
            state.moving = true
            try {
                Animatable(from).animateTo(to, spring(stiffness = 600f)) {
                    state.setTransform(state.scale, state.panX, value)
                }
            } finally {
                state.moving = false
            }
        }
    }
}

/** 필기 한 번(손가락을 대서 뗄 때까지) */
private class InkSession(
    private val state: DocViewState,
    private val ink: InkState,
    private val paint: Paint,
    private val page: Int,
) {
    private val points = ArrayList<Offset>()
    private val erased = ArrayList<Mark>()
    private val aspect = state.pageHeight(page) / state.baseWidth

    companion object {
        fun start(state: DocViewState, ink: InkState, paint: Paint, at: Offset): InkSession? {
            // 글자 도구는 탭으로 처리한다 (Gestures.openText)
            if (ink.tool == InkTool.TEXT) return null
            val (page, p) = state.hitPage(at) ?: return null
            return InkSession(state, ink, paint, page).also { it.begin(p) }
        }
    }

    private fun begin(p: Offset) {
        when (ink.tool) {
            InkTool.PEN, InkTool.HIGHLIGHTER -> {
                points += p
                ink.live = stroke()
            }
            InkTool.ERASER -> erase(p)
            InkTool.TEXT -> Unit
        }
    }

    private fun stroke(): Stroke {
        val hl = ink.tool == InkTool.HIGHLIGHTER
        return Stroke(
            page = page,
            points = points.toList(),
            color = if (hl) ink.highlighterColor else ink.penColor,
            width = if (hl) InkState.HIGHLIGHTER_WIDTH else ink.penWidth,
            highlighter = hl,
        )
    }

    private fun erase(p: Offset) {
        val hits = ink.hitTest(page, p, InkState.ERASER_RADIUS, aspect, paint).filter { it !in erased }
        if (hits.isNotEmpty()) {
            erased += hits
            ink.remove(hits)
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    fun move(c: androidx.compose.ui.input.pointer.PointerInputChange) {
        fun add(screen: Offset) {
            val p = state.screenToPage(page, screen)
            when (ink.tool) {
                InkTool.PEN, InkTool.HIGHLIGHTER -> {
                    val q = Offset(p.x.coerceIn(0f, 1f), p.y.coerceIn(0f, 1f))
                    val last = points.last()
                    // 거의 같은 자리는 건너뛴다 (파일이 쓸데없이 커지지 않게)
                    if (hypot(q.x - last.x, (q.y - last.y) * aspect) > 0.0012f / state.scale) points += q
                }
                InkTool.ERASER -> erase(p)
                InkTool.TEXT -> Unit
            }
        }
        c.historical.forEach { add(it.position) }
        add(c.position)
        if (ink.tool == InkTool.PEN || ink.tool == InkTool.HIGHLIGHTER) ink.live = stroke()
    }

    fun cancel() {
        ink.live = null
        points.clear()
    }

    fun finish(cancelled: Boolean) {
        if (ink.tool != InkTool.PEN && ink.tool != InkTool.HIGHLIGHTER) return
        val s = ink.live
        ink.live = null
        if (!cancelled && s != null && points.isNotEmpty()) ink.add(stroke())
    }
}

/** 쪽 크기 [w]x[h] 판에 필기 하나 (쪽 왼쪽 위 기준) */
internal fun DrawScope.drawMarkSized(m: Mark, paint: Paint, w: Float, h: Float) {
    when (m) {
        is Stroke -> {
            if (m.points.isEmpty()) return
            val path = androidx.compose.ui.graphics.Path()
            path.moveTo(m.points[0].x * w, m.points[0].y * h)
            if (m.points.size == 1) path.lineTo(m.points[0].x * w + 0.5f, m.points[0].y * h)
            for (i in 1 until m.points.size) path.lineTo(m.points[i].x * w, m.points[i].y * h)
            drawPath(
                path,
                color = Color(m.color).copy(alpha = if (m.highlighter) PdfAnnotator.HIGHLIGHTER_ALPHA else 1f),
                style = androidx.compose.ui.graphics.drawscope.Stroke(
                    width = m.width * w,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round,
                    join = androidx.compose.ui.graphics.StrokeJoin.Round,
                ),
                blendMode = if (m.highlighter) androidx.compose.ui.graphics.BlendMode.Multiply else androidx.compose.ui.graphics.BlendMode.SrcOver,
            )
        }
        is TextNote -> {
            paint.color = m.color
            paint.textSize = m.size * w
            drawContext.canvas.nativeCanvas.let { canvas ->
                m.lines.forEachIndexed { i, line ->
                    val baseline = m.y * h + (i * TEXT_LINE_HEIGHT + TEXT_ASCENT) * m.size * w
                    canvas.drawText(line, m.x * w, baseline, paint)
                }
            }
        }
    }
}
