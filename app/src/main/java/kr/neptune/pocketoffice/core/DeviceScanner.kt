package kr.neptune.pocketoffice.core

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** 폰 저장소에서 찾은 문서 */
data class DeviceDoc(
    val path: String,
    val name: String,
    val size: Long,
    val modified: Long,
    /** "Download", "Documents/회사" 처럼 저장소 기준 상대 폴더 */
    val folder: String,
) {
    val uri: Uri get() = Uri.fromFile(File(path))
    val format: DocFormat? get() = DocFormat.fromName(name)
}

/**
 * "모든 파일 접근" 을 켜면 폰 안의 문서를 한 번에 보여 준다.
 *
 * 폴더를 직접 훑지 않고 MediaStore 에 묻는다. 시스템이 이미 색인해 둔 것이라
 * 수천 개가 있어도 순식간에 나온다. 다운로드·카카오톡 받은 파일·문서 폴더가 모두 여기 잡힌다.
 */
object DeviceScanner {

    private const val TAG = "DeviceScanner"

    fun hasAccess(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }

    /** 안드로이드 11 이상은 설정 화면으로 보내야 한다. 그 아래는 런타임 권한 요청으로 충분하다 */
    fun accessSettingsIntent(context: Context): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + context.packageName))
        } else {
            null
        }

    val legacyPermissions = arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)

    suspend fun scan(context: Context): List<DeviceDoc> = withContext(Dispatchers.IO) {
        if (!hasAccess(context)) return@withContext emptyList()

        val exts = DocFormat.listed.map { it.ext }
        val selection = exts.joinToString(" OR ", "(", ")") { "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?" }
        val args = exts.map { "%.$it" }.toTypedArray()
        @Suppress("DEPRECATION")
        val projection = arrayOf(
            MediaStore.Files.FileColumns.DATA,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
        )
        val root = Environment.getExternalStorageDirectory().absolutePath

        val out = ArrayList<DeviceDoc>()
        runCatching {
            context.contentResolver.query(
                MediaStore.Files.getContentUri("external"),
                projection,
                selection,
                args,
                "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC",
            )?.use { c ->
                val iData = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA)
                val iName = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                val iSize = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
                val iDate = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)
                while (c.moveToNext()) {
                    val path = c.getString(iData) ?: continue
                    val name = c.getString(iName) ?: File(path).name
                    // 오피스가 열어 둔 동안 만드는 잠금 파일(~$보고서.docx)과 숨김 파일은 뺀다
                    if (name.startsWith("~$") || name.startsWith(".")) continue
                    if (path.contains("/.")) continue
                    val parent = File(path).parent ?: ""
                    val folder = parent.removePrefix(root).trim('/').ifEmpty { "내장 메모리" }
                    out += DeviceDoc(path, name, c.getLong(iSize), c.getLong(iDate) * 1000, folder)
                }
            }
        }.onFailure { Log.w(TAG, "문서 목록을 읽지 못했습니다", it) }
        out
    }

    /** 새 문서를 바로 저장할 기본 폴더. 모든 파일 접근이 있을 때만 쓴다 */
    fun defaultFolder(): File =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "PocketOffice")
}
