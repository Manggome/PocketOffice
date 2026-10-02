package kr.neptune.pocketoffice.viewer

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kr.neptune.pocketoffice.ui.PageCommand
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * PDF 발표 화면. 검은 바탕에 한 쪽씩 화면에 꽉 맞춰 보여 준다.
 *
 * 넘기기: 오른쪽/왼쪽 탭, 옆으로 밀기, 블루투스 리모컨·키보드([commands]).
 * 가운데를 누르면 쪽 번호와 닫기 버튼이 잠깐 나온다. 다음 쪽은 미리 그려 둬서 바로 넘어간다.
 */
@Composable
fun PdfPresentation(
    doc: PdfDoc,
    startPage: Int,
    commands: Flow<PageCommand>,
    onExit: (lastPage: Int) -> Unit,
) {
    var page by remember { mutableIntStateOf(startPage.coerceIn(0, doc.pageCount - 1)) }
    var black by remember { mutableStateOf(false) }
    var overlay by remember { mutableStateOf(true) }
    var forward by remember { mutableStateOf(true) }

    fun go(delta: Int) {
        val next = (page + delta).coerceIn(0, doc.pageCount - 1)
        if (next == page) return
        forward = delta > 0
        black = false
        page = next
    }

    LaunchedEffect(commands) {
        commands.collect { cmd ->
            when (cmd) {
                PageCommand.NEXT -> go(1)
                PageCommand.PREV -> go(-1)
                PageCommand.BLACK -> black = !black
                PageCommand.EXIT -> onExit(page)
                PageCommand.START -> Unit
            }
        }
    }

    // 처음에 잠깐 보여 준 쪽 번호·닫기 버튼은 저절로 숨긴다
    LaunchedEffect(overlay, page) {
        if (overlay) {
            delay(2500)
            overlay = false
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val w = constraints.maxWidth
        val h = constraints.maxHeight
        val swipe = with(LocalDensity.current) { 56.dp.toPx() }

        // 앞뒤 쪽을 미리 그려 둔다. 넘길 때 기다리지 않게
        LaunchedEffect(page, w, h) {
            for (p in listOf(page + 1, page - 1)) {
                if (p in 0 until doc.pageCount) doc.render(p, fitWidth(doc, p, w, h))
            }
        }

        AnimatedContent(
            targetState = page,
            transitionSpec = {
                val dir = if (forward) 1 else -1
                (slideInHorizontally { it * dir / 3 } + fadeIn()) togetherWith (slideOutHorizontally { -it * dir / 3 } + fadeOut())
            },
            label = "slide",
            modifier = Modifier.fillMaxSize(),
        ) { p ->
            PresentPage(doc, p, w, h)
        }

        // 손가락: 밀면 넘기고, 양옆을 누르면 넘기고, 가운데를 누르면 쪽 번호
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(w) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        var last = down
                        do {
                            val ev = awaitPointerEvent()
                            last = ev.changes.first()
                        } while (ev.changes.any { it.pressed })
                        val dx = last.position.x - down.position.x
                        val dy = last.position.y - down.position.y
                        when {
                            abs(dx) > swipe && abs(dx) > abs(dy) -> go(if (dx < 0) 1 else -1)
                            abs(dx) < swipe / 3 && abs(dy) < swipe / 3 -> {
                                val x = down.position.x / w
                                when {
                                    x > 0.66f -> go(1)
                                    x < 0.34f -> go(-1)
                                    else -> overlay = !overlay
                                }
                            }
                        }
                    }
                },
        )

        if (black) Box(Modifier.fillMaxSize().background(Color.Black))

        AnimatedVisibility(visible = overlay, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopEnd)) {
            IconButton(onClick = { onExit(page) }, modifier = Modifier.padding(12.dp)) {
                Icon(Icons.Outlined.Close, contentDescription = "발표 끝내기", tint = Color.White, modifier = Modifier.size(28.dp))
            }
        }
        AnimatedVisibility(visible = overlay, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
            Surface(
                shape = RoundedCornerShape(50),
                color = Color(0xAA303134),
                contentColor = Color.White,
                modifier = Modifier.padding(bottom = 20.dp),
            ) {
                Text(
                    "${page + 1} / ${doc.pageCount}",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }
        }
    }
}

/** 화면 [w]x[h] 안에 쪽이 통째로 들어가는 너비 */
private fun fitWidth(doc: PdfDoc, page: Int, w: Int, h: Int): Int {
    val aspect = doc.aspect(page)
    return min(w.toFloat(), h / aspect).roundToInt().coerceAtLeast(1)
}

@Composable
private fun PresentPage(doc: PdfDoc, page: Int, w: Int, h: Int) {
    val width = fitWidth(doc, page, w, h)
    var bitmap by remember(page) { mutableStateOf<Bitmap?>(doc.cached(page, width)) }
    LaunchedEffect(page, width) { doc.render(page, width)?.let { bitmap = it } }
    val density = LocalDensity.current
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val pw = with(density) { width.toDp() }
        val ph = with(density) { (width * doc.aspect(page)).toDp() }
        Box(Modifier.size(pw, ph).background(Color.White)) {
            bitmap?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = "${page + 1}쪽",
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
