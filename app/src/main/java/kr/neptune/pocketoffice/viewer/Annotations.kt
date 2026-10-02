package kr.neptune.pocketoffice.viewer

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import kotlin.math.hypot

/*
 * PDF 위의 간단한 필기: 펜, 형광펜, 글자, 지우개.
 *
 * 좌표는 모두 "보이는 쪽" 기준 0..1 비율이다 (왼쪽 위가 0,0). 화면 크기·확대와 상관없이 같은 자리를
 * 가리키고, 저장할 때 PdfAnnotator 가 쪽의 회전·잘림 상자를 따져 PDF 좌표로 바꾼다.
 * 굵기와 글자 크기는 쪽 너비에 대한 비율이다.
 */

enum class InkTool(val label: String) { PEN("펜"), HIGHLIGHTER("형광펜"), TEXT("글자"), ERASER("지우개") }

sealed interface Mark {
    val page: Int
    /** 같은 자리에 같은 것을 두 번 넣어도 따로 지우고 되돌릴 수 있게 */
    val id: Long
}

private val idSeq = java.util.concurrent.atomic.AtomicLong()
fun nextMarkId(): Long = idSeq.incrementAndGet()

data class Stroke(
    override val page: Int,
    val points: List<Offset>,
    val color: Int,
    val width: Float,
    val highlighter: Boolean,
    override val id: Long = nextMarkId(),
) : Mark

data class TextNote(
    override val page: Int,
    val x: Float,
    val y: Float,
    val text: String,
    val size: Float,
    val color: Int,
    override val id: Long = nextMarkId(),
) : Mark {
    val lines: List<String> get() = text.split('\n')
}

/** 글자 칸의 줄 간격과 첫 줄 기준선 (글자 크기에 대한 배수). 화면과 저장이 같은 값을 쓴다 */
const val TEXT_LINE_HEIGHT = 1.25f
const val TEXT_ASCENT = 0.92f

/** 화면에 글자를 그리고 크기를 잴 때 쓰는 글꼴. 저장에 넣는 것과 같은 글꼴이라 보이는 그대로 들어간다 */
object InkFont {
    @Volatile
    private var typeface: Typeface? = null

    fun get(context: Context): Typeface =
        typeface ?: runCatching { Typeface.createFromAsset(context.assets, "fonts/NanumGothic.ttf") }
            .getOrDefault(Typeface.DEFAULT)
            .also { typeface = it }
}

/** 필기 중인 상태 하나. 실행 취소 / 다시 실행까지 들고 있다 */
class InkState {

    val marks = mutableStateListOf<Mark>()

    var tool by mutableStateOf(InkTool.PEN)
    var penColor by mutableStateOf(PEN_COLORS[0])
    var penWidth by mutableStateOf(PEN_WIDTHS[1])
    var highlighterColor by mutableStateOf(HIGHLIGHTER_COLORS[0])
    var textColor by mutableStateOf(PEN_COLORS[0])
    var textSize by mutableStateOf(TEXT_SIZES[1])

    /** 지금 긋고 있는 선 (아직 marks 에 들어가지 않음) */
    var live by mutableStateOf<Stroke?>(null)

    /** 글자 넣기/고치기 대화상자 */
    var textDialog by mutableStateOf<TextDialog?>(null)

    data class TextDialog(val page: Int, val x: Float, val y: Float, val editing: TextNote?)

    private sealed interface Action {
        data class Add(val mark: Mark) : Action
        data class Remove(val marks: List<Mark>) : Action
        data class Replace(val old: Mark, val new: Mark) : Action
    }

    private val undoStack = mutableStateListOf<Action>()
    private val redoStack = mutableStateListOf<Action>()

    val canUndo get() = undoStack.isNotEmpty()
    val canRedo get() = redoStack.isNotEmpty()
    val isEmpty get() = marks.isEmpty()

    fun add(mark: Mark) = record(Action.Add(mark))

    fun remove(list: List<Mark>) {
        if (list.isNotEmpty()) record(Action.Remove(list))
    }

    fun replace(old: Mark, new: Mark) = record(Action.Replace(old, new))

    private fun record(a: Action) {
        apply(a)
        undoStack += a
        redoStack.clear()
    }

    private fun apply(a: Action) {
        when (a) {
            is Action.Add -> marks += a.mark
            is Action.Remove -> marks.removeAll(a.marks.toSet())
            is Action.Replace -> {
                val i = marks.indexOf(a.old)
                if (i >= 0) marks[i] = a.new else marks += a.new
            }
        }
    }

    private fun revert(a: Action) {
        when (a) {
            is Action.Add -> marks.remove(a.mark)
            is Action.Remove -> marks.addAll(a.marks)
            is Action.Replace -> {
                val i = marks.indexOf(a.new)
                if (i >= 0) marks[i] = a.old
            }
        }
    }

    fun undo() {
        val a = undoStack.removeLastOrNull() ?: return
        revert(a)
        redoStack += a
    }

    fun redo() {
        val a = redoStack.removeLastOrNull() ?: return
        apply(a)
        undoStack += a
    }

    fun clear() {
        marks.clear()
        undoStack.clear()
        redoStack.clear()
        live = null
        textDialog = null
    }

    /** 지우개: [p] 근처를 지나는 선과 [p] 를 덮는 글자 칸 */
    fun hitTest(page: Int, p: Offset, radius: Float, aspect: Float, paint: Paint): List<Mark> =
        marks.filter { m ->
            m.page == page && when (m) {
                is Stroke -> m.points.any { q -> hypot((q.x - p.x).toDouble(), ((q.y - p.y) * aspect).toDouble()) < radius + m.width / 2 }
                is TextNote -> textBounds(m, aspect, paint).contains(p)
            }
        }

    /** 글자 칸이 차지하는 범위 (비율 좌표). 쪽의 세로/가로 비율 [aspect] 로 세로를 맞춘다 */
    fun textBounds(n: TextNote, aspect: Float, paint: Paint): androidx.compose.ui.geometry.Rect {
        paint.textSize = 1000f
        val widest = n.lines.maxOfOrNull { paint.measureText(it) } ?: 0f
        val w = widest / 1000f * n.size
        val h = n.lines.size * TEXT_LINE_HEIGHT * n.size / aspect
        return androidx.compose.ui.geometry.Rect(n.x, n.y, n.x + w.coerceAtLeast(n.size), n.y + h)
    }

    companion object {
        val PEN_COLORS = listOf(0xFF111111.toInt(), 0xFFE53935.toInt(), 0xFF1E66F5.toInt(), 0xFF2E7D32.toInt(), 0xFFFB8C00.toInt())
        val HIGHLIGHTER_COLORS = listOf(0xFFFFEB3B.toInt(), 0xFF7CFC00.toInt(), 0xFF40C4FF.toInt(), 0xFFFF80AB.toInt())
        /** 쪽 너비에 대한 굵기. A4 에서 대략 0.9 / 1.8 / 3.6 pt */
        val PEN_WIDTHS = listOf(0.0015f, 0.003f, 0.006f)
        const val HIGHLIGHTER_WIDTH = 0.022f
        /** 쪽 너비에 대한 글자 크기. A4 에서 대략 12 / 17 / 27 pt */
        val TEXT_SIZES = listOf(0.02f, 0.028f, 0.045f)
        const val ERASER_RADIUS = 0.018f
    }
}
