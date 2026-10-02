package kr.neptune.pocketoffice.viewer

import android.content.Context
import androidx.compose.ui.geometry.Offset
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.graphics.blend.BlendMode
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.util.Matrix
import java.io.File
import kotlin.math.hypot

/**
 * 필기를 PDF 에 써 넣는다.
 *
 * 주석(annotation) 객체가 아니라 쪽의 내용에 직접 그린다. 주석은 보는 앱마다 그리는 방식이 달라
 * 어떤 뷰어에서는 안 보이기도 하는데, 내용에 그리면 어디서 열어도 똑같이 보인다.
 * 글자는 함께 넣은 나눔고딕에서 쓴 글자만 골라 담는다 (파일이 크게 늘지 않는다).
 */
object PdfAnnotator {

    @Volatile
    private var ready = false

    private fun init(context: Context) {
        if (ready) return
        PDFBoxResourceLoader.init(context.applicationContext)
        ready = true
    }

    fun apply(context: Context, input: File, output: File, marks: List<Mark>) {
        init(context)
        PDDocument.load(input, MemoryUsageSetting.setupMixed(64L * 1024 * 1024)).use { doc ->
            // 열기 암호 없이 열리는 "권한 암호" PDF 는 그대로 두면 저장이 막힌다
            if (doc.isEncrypted) doc.isAllSecurityToBeRemoved = true

            val font by lazy {
                context.assets.open("fonts/NanumGothic.ttf").use { PDType0Font.load(doc, it, true) }
            }

            marks.groupBy { it.page }.forEach { (index, list) ->
                if (index !in 0 until doc.numberOfPages) return@forEach
                val page = doc.getPage(index)
                val geo = Geometry(page.cropBox, page.rotation)
                PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                    list.filterIsInstance<Stroke>().forEach { drawStroke(cs, geo, it) }
                    list.filterIsInstance<TextNote>().forEach { drawText(cs, geo, it, font) }
                }
            }
            doc.save(output)
        }
    }

    private fun drawStroke(cs: PDPageContentStream, geo: Geometry, s: Stroke) {
        if (s.points.isEmpty()) return
        cs.saveGraphicsState()
        if (s.highlighter) {
            val gs = PDExtendedGraphicsState()
            gs.strokingAlphaConstant = HIGHLIGHTER_ALPHA
            gs.blendMode = BlendMode.MULTIPLY
            cs.setGraphicsStateParameters(gs)
        }
        cs.setStrokingColor(red(s.color), green(s.color), blue(s.color))
        cs.setLineWidth(s.width * geo.displayWidth)
        cs.setLineCapStyle(1) // 둥근 끝
        cs.setLineJoinStyle(1)
        val first = geo.map(s.points[0])
        cs.moveTo(first.x, first.y)
        if (s.points.size == 1) {
            // 콕 찍은 점
            cs.lineTo(first.x + 0.01f, first.y)
        } else {
            for (i in 1 until s.points.size) {
                val p = geo.map(s.points[i])
                cs.lineTo(p.x, p.y)
            }
        }
        cs.stroke()
        cs.restoreGraphicsState()
    }

    private fun drawText(cs: PDPageContentStream, geo: Geometry, n: TextNote, font: PDType0Font) {
        val sizePt = n.size * geo.displayWidth
        // 보이는 쪽에서 오른쪽 / 위쪽이 PDF 안에서 어느 방향인지 (쪽이 돌아가 있으면 글자도 돌린다)
        val dir = geo.direction(Offset(1f, 0f))
        val up = geo.direction(Offset(0f, -1f))
        // 글자 크기는 너비 비율이라 세로 비율로 바꿔야 줄 위치가 맞는다
        val toV = geo.displayWidth / geo.displayHeight
        cs.saveGraphicsState()
        cs.beginText()
        cs.setFont(font, sizePt)
        cs.setNonStrokingColor(red(n.color), green(n.color), blue(n.color))
        n.lines.forEachIndexed { i, line ->
            val baselineV = n.y + (i * TEXT_LINE_HEIGHT + TEXT_ASCENT) * n.size * toV
            val o = geo.map(Offset(n.x, baselineV))
            cs.setTextMatrix(Matrix(dir.x, dir.y, up.x, up.y, o.x, o.y))
            cs.showText(printable(font, line))
        }
        cs.endText()
        cs.restoreGraphicsState()
    }

    /** 글꼴에 없는 글자(이모지 등)는 빼고 쓴다. 하나 때문에 저장이 통째로 실패하지 않게 */
    private fun printable(font: PDType0Font, text: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val ch = String(Character.toChars(cp))
            if (runCatching { font.encode(ch) }.isSuccess) sb.append(ch)
            i += Character.charCount(cp)
        }
        return sb.toString()
    }

    private fun red(c: Int) = ((c shr 16) and 0xFF) / 255f
    private fun green(c: Int) = ((c shr 8) and 0xFF) / 255f
    private fun blue(c: Int) = (c and 0xFF) / 255f

    const val HIGHLIGHTER_ALPHA = 0.35f

    /**
     * 화면에 보이는 쪽(0..1, 왼쪽 위 기준)과 PDF 좌표(pt, 왼쪽 아래 기준) 사이의 변환.
     * 안드로이드 PdfRenderer 는 잘림 상자(CropBox)를 /Rotate 만큼 시계 방향으로 돌려 그린다.
     */
    class Geometry(box: PDRectangle, rotation: Int) {
        private val llx = box.lowerLeftX
        private val lly = box.lowerLeftY
        private val w = box.width
        private val h = box.height
        private val r = ((rotation % 360) + 360) % 360

        val displayWidth: Float = if (r == 90 || r == 270) h else w
        val displayHeight: Float = if (r == 90 || r == 270) w else h

        fun map(p: Offset): Offset {
            val u = p.x
            val v = p.y
            return when (r) {
                90 -> Offset(llx + v * w, lly + u * h)
                180 -> Offset(llx + (1 - u) * w, lly + v * h)
                270 -> Offset(llx + (1 - v) * w, lly + (1 - u) * h)
                else -> Offset(llx + u * w, lly + (1 - v) * h)
            }
        }

        /** 보이는 쪽의 한 방향이 PDF 안에서 가리키는 단위 벡터 */
        fun direction(d: Offset): Offset {
            val a = map(Offset(0.5f, 0.5f))
            val b = map(Offset(0.5f + d.x * 0.1f, 0.5f + d.y * 0.1f))
            val dx = b.x - a.x
            val dy = b.y - a.y
            val len = hypot(dx, dy).coerceAtLeast(1e-6f)
            return Offset(dx / len, dy / len)
        }
    }
}
