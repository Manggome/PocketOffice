package kr.neptune.pocketoffice.viewer

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.util.concurrent.Executors
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** 암호가 걸려 안드로이드 기본 렌더러로는 열 수 없는 PDF */
class PdfPasswordException : Exception("암호가 걸린 PDF 입니다")

/**
 * 안드로이드 기본 PdfRenderer 로 PDF 를 그린다. 삼성 기본 뷰어와 같은 엔진이라 가볍고 빠르다.
 *
 * PdfRenderer 는 한 번에 한 쪽만 열 수 있어서 그리기는 전용 스레드 하나에서만 한다.
 * 그린 쪽은 크기별로 캐시에 두고, 메모리의 일정 몫을 넘으면 오래된 것부터 버린다.
 */
class PdfDoc private constructor(
    private val pfd: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
    /** 열려고 복사해 둔 임시 파일 (원본이 임의 접근을 지원하지 않을 때). 인쇄에도 쓴다 */
    val localCopy: File?,
    /** 쪽마다 (가로, 세로) — PDF 단위(1/72 인치) */
    val pageSizes: List<Pair<Int, Int>>,
) : Closeable {

    val pageCount: Int get() = pageSizes.size

    private data class Key(val index: Int, val width: Int)

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "pdf-render").apply { priority = Thread.NORM_PRIORITY + 1 }
    }
    private val renderThread = executor.asCoroutineDispatcher()

    private val cache = object : LruCache<Key, Bitmap>(cacheBytes()) {
        override fun sizeOf(key: Key, value: Bitmap) = value.byteCount
    }

    @Volatile
    private var closed = false

    /** 세로/가로 비율 */
    fun aspect(index: Int): Float {
        val (w, h) = pageSizes[index]
        return if (w > 0) h.toFloat() / w else 1.414f
    }

    /** 이미 그려 둔 것 중 [width] 에 가장 가까운 것. 확대하는 동안 흐린 것이라도 먼저 보여 주려고 */
    fun cached(index: Int, width: Int): Bitmap? = cache.get(Key(index, bucket(width)))

    /**
     * [index] 쪽을 가로 [width] 픽셀로 그린다. 너무 크면(확대) 픽셀 수 상한에 맞춰 줄인다 —
     * 그 경우 화면에서 조금 늘려 보여 준다.
     */
    suspend fun render(index: Int, width: Int): Bitmap? {
        val w0 = bucket(width)
        cache.get(Key(index, w0))?.let { return it }
        return withContext(renderThread) {
            coroutineContext.ensureActive()
            if (closed) return@withContext null
            cache.get(Key(index, w0))?.let { return@withContext it }

            var w = w0
            var h = (w * aspect(index)).roundToInt().coerceAtLeast(1)
            val pixels = w.toLong() * h
            if (pixels > MAX_PIXELS) {
                val s = sqrt(MAX_PIXELS.toDouble() / pixels)
                w = (w * s).toInt().coerceAtLeast(1)
                h = (h * s).toInt().coerceAtLeast(1)
            }
            try {
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.WHITE)
                renderer.openPage(index).use { it.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
                cache.put(Key(index, w0), bmp)
                bmp
            } catch (t: Throwable) {
                Log.w(TAG, "쪽 $index 그리기 실패", t)
                null
            }
        }
    }

    /**
     * 확대했을 때 화면에 보이는 부분만 화면 해상도 그대로 그린다 (캐시하지 않는다).
     *
     * 쪽 전체를 5배 크기로 그리면 메모리가 터지므로, 보이는 [tileW]x[tileH] 픽셀만 그린다.
     * [pageWidthPx] 는 지금 배율에서 쪽 전체의 가로 픽셀, ([left],[top]) 은 그중 이 조각이 시작하는 자리.
     */
    suspend fun renderTile(index: Int, pageWidthPx: Float, left: Float, top: Float, tileW: Int, tileH: Int): Bitmap? {
        if (tileW <= 0 || tileH <= 0) return null
        return withContext(renderThread) {
            coroutineContext.ensureActive()
            if (closed) return@withContext null
            try {
                val bmp = Bitmap.createBitmap(tileW, tileH, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.WHITE)
                val (wPt, _) = pageSizes[index]
                // PdfRenderer 의 변환은 쪽의 점(1/72 인치, 왼쪽 위 원점) → 비트맵 픽셀
                val m = android.graphics.Matrix().apply {
                    postScale(pageWidthPx / wPt, pageWidthPx / wPt)
                    postTranslate(-left, -top)
                }
                renderer.openPage(index).use { it.render(bmp, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
                bmp
            } catch (t: Throwable) {
                Log.w(TAG, "쪽 $index 조각 그리기 실패", t)
                null
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        // 그리는 중일 수 있으니 같은 스레드에서 닫는다
        executor.execute {
            runCatching { renderer.close() }
            runCatching { pfd.close() }
            cache.evictAll()
            localCopy?.delete()
        }
        executor.shutdown()
    }

    companion object {
        private const val TAG = "PdfDoc"

        /** 한 장의 최대 픽셀 수 (약 48MB). 폴드를 펴고 크게 확대해도 메모리가 터지지 않게 */
        private const val MAX_PIXELS = 12_000_000L

        /** 조금씩 다른 너비마다 새로 그리지 않게 64픽셀 단위로 맞춘다 */
        private fun bucket(width: Int) = ((width + 63) / 64 * 64).coerceAtLeast(64)

        private fun cacheBytes(): Int = (Runtime.getRuntime().maxMemory() / 4).coerceAtMost(256L * 1024 * 1024).toInt()

        suspend fun open(context: Context, uri: Uri): PdfDoc = withContext(Dispatchers.IO) {
            try {
                openWith(openDirect(context, uri), null)
            } catch (e: SecurityException) {
                throw PdfPasswordException()
            } catch (first: Throwable) {
                // 메신저 첨부처럼 임의 접근이 안 되는 스트림이면 한 번 복사해서 연다
                Log.i(TAG, "바로 열기 실패, 복사해서 엽니다: ${first.message}")
                val copy = File(File(context.cacheDir, "viewer").apply { mkdirs() }, "open-${System.currentTimeMillis()}.pdf")
                context.contentResolver.openInputStream(uri)!!.use { input -> copy.outputStream().use { input.copyTo(it) } }
                try {
                    openWith(ParcelFileDescriptor.open(copy, ParcelFileDescriptor.MODE_READ_ONLY), copy)
                } catch (e: SecurityException) {
                    copy.delete()
                    throw PdfPasswordException()
                }
            }
        }

        private fun openDirect(context: Context, uri: Uri): ParcelFileDescriptor =
            if (uri.scheme == ContentResolver.SCHEME_FILE) {
                ParcelFileDescriptor.open(File(uri.path!!), ParcelFileDescriptor.MODE_READ_ONLY)
            } else {
                context.contentResolver.openFileDescriptor(uri, "r") ?: error("열 수 없습니다")
            }

        private fun openWith(pfd: ParcelFileDescriptor, copy: File?): PdfDoc {
            val renderer = try {
                PdfRenderer(pfd)
            } catch (t: Throwable) {
                runCatching { pfd.close() }
                throw t
            }
            val sizes = (0 until renderer.pageCount).map { i ->
                renderer.openPage(i).use { it.width to it.height }
            }
            return PdfDoc(pfd, renderer, copy, sizes)
        }
    }
}
