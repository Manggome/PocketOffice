package kr.neptune.pocketoffice.core

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

/** 열려는 파일에 대해 미리 알 수 있는 것 */
data class DocMeta(
    val name: String,
    val size: Long,
    val lastModified: Long,
    val mime: String?,
)

/**
 * content:// 와 file:// 를 같은 방식으로 읽고 쓴다.
 *
 * file:// 은 "모든 파일 접근" 을 켠 뒤 폰 전체 목록에서 고른 경우다.
 * 그 외에는 모두 SAF(파일 열기 화면)나 다른 앱이 넘겨준 content:// 이다.
 */
object DocIo {

    private const val TAG = "DocIo"

    fun meta(context: Context, uri: Uri): DocMeta {
        if (uri.scheme == ContentResolver.SCHEME_FILE) {
            val file = File(uri.path ?: "")
            return DocMeta(file.name, file.length(), file.lastModified(), DocFormat.fromName(file.name)?.mime)
        }

        val cr = context.contentResolver
        var name: String? = null
        var size = -1L
        var modified = 0L
        runCatching {
            cr.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = c.getString(it) }
                    c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let { if (!c.isNull(it)) size = c.getLong(it) }
                    c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED).takeIf { it >= 0 }
                        ?.let { if (!c.isNull(it)) modified = c.getLong(it) }
                }
            }
        }.onFailure { Log.w(TAG, "meta 질의 실패: $uri", it) }

        val mime = runCatching { cr.getType(uri) }.getOrNull()
        val fallback = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.contains('.') } ?: "문서"
        var finalName = name?.takeIf { it.isNotBlank() } ?: fallback

        // 이름에 확장자가 없으면(일부 메신저) MIME 으로 붙여 준다. 편집기는 이름의 확장자로 형식을 고른다
        if (DocFormat.fromName(finalName) == null) {
            DocFormat.fromMime(mime)?.let { finalName = "$finalName.${it.ext}" }
        }
        return DocMeta(finalName, size, modified, mime)
    }

    fun openInput(context: Context, uri: Uri): InputStream {
        if (uri.scheme == ContentResolver.SCHEME_FILE) return File(uri.path ?: "").inputStream()
        return context.contentResolver.openInputStream(uri) ?: throw IOException("열 수 없습니다: $uri")
    }

    /**
     * [src] 의 내용으로 [uri] 를 덮어쓴다.
     *
     * "wt"(잘라 내고 쓰기)를 먼저 시도한다. 그냥 "w" 는 일부 제공자에서 기존 길이를 남겨 두는데,
     * 새 파일이 더 짧으면 끝에 옛 바이트가 붙어 zip(docx/xlsx/pptx)이 깨진다. 그래서 "wt" 를
     * 지원하지 않는 곳은 "rw" 로 열어 직접 잘라 낸다.
     */
    fun writeBack(context: Context, uri: Uri, src: File) {
        if (uri.scheme == ContentResolver.SCHEME_FILE) {
            val target = File(uri.path ?: throw IOException("경로가 없습니다"))
            // 같은 폴더에 임시 파일을 만든 뒤 바꿔치기한다. 쓰다가 끊겨도 원본이 남는다
            val tmp = File(target.parentFile, ".${target.name}.pocket-tmp")
            src.inputStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
            if (!tmp.renameTo(target)) {
                tmp.delete()
                src.inputStream().use { input -> target.outputStream().use { input.copyTo(it) } }
            }
            return
        }

        val cr = context.contentResolver
        val out = runCatching { cr.openOutputStream(uri, "wt") }.getOrNull()
        if (out != null) {
            out.use { o -> src.inputStream().use { it.copyTo(o) } }
            return
        }

        val pfd = cr.openFileDescriptor(uri, "rw") ?: throw IOException("쓰기 권한이 없습니다")
        pfd.use {
            FileOutputStream(it.fileDescriptor).use { fos ->
                val channel = fos.channel
                channel.position(0)
                src.inputStream().use { input -> input.copyTo(fos) }
                channel.truncate(src.length())
            }
        }
    }

    /** 파일 열기 화면으로 고른 문서는 앱을 다시 켜도 열 수 있게 권한을 붙잡아 둔다 */
    fun persist(context: Context, uri: Uri) {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return
        val cr = context.contentResolver
        val rw = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { cr.takePersistableUriPermission(uri, rw) }
            .recoverCatching { cr.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    fun release(context: Context, uri: Uri) {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return
        val held = context.contentResolver.persistedUriPermissions.any { it.uri == uri }
        if (!held) return
        runCatching {
            context.contentResolver.releasePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
    }

    /** 다시 열 수 있는 문서인가. 다른 앱이 잠깐 빌려준 uri 는 앱을 끄면 못 연다 */
    fun isReachable(context: Context, uri: Uri): Boolean = runCatching {
        openInput(context, uri).use { true }
    }.getOrDefault(false)

    /**
     * 처음 덮어쓰기 직전에 원본을 앱 안에 한 부 남긴다. 편집기가 잘못 저장해도 되돌릴 수 있게.
     * 최근 [KEEP_BACKUPS] 개만 둔다.
     */
    fun backup(context: Context, uri: Uri, name: String) {
        val dir = File(context.filesDir, "backups").apply { mkdirs() }
        runCatching {
            val target = File(dir, "${System.currentTimeMillis()}-${safeFileName(name)}")
            openInput(context, uri).use { input -> target.outputStream().use { input.copyTo(it) } }
            dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(KEEP_BACKUPS)?.forEach { it.delete() }
        }.onFailure { Log.w(TAG, "백업 실패", it) }
    }

    fun backupDir(context: Context): File = File(context.filesDir, "backups")

    private const val KEEP_BACKUPS = 15

    fun safeFileName(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").take(120).ifBlank { "문서" }
}
