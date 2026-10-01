package kr.neptune.pocketoffice.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** 저장하지 않고 화면을 떠난 문서의 사본 */
data class Snapshot(
    val key: String,
    /** 원래 파일. 새로 만든 문서면 null */
    val sourceUri: String?,
    /** 화면에 보여 줄 이름 (확장자 포함, 사본의 형식 기준) */
    val name: String,
    val savedAt: Long,
    val file: File,
)

/**
 * 고친 내용을 저장하지 않은 채 앱이 뒤로 가거나, 시스템이 메모리 때문에 앱을 내리면
 * 편집기 안의 내용이 사라진다. 화면을 벗어날 때마다 사본을 앱 안에 떠 두고,
 * 같은 문서를 다시 열거나 첫 화면에서 "복구" 를 누르면 그 사본으로 이어 간다.
 *
 * 사용자 파일에는 절대 쓰지 않는다. 저장에 성공하거나 "버리기" 를 고르면 지운다.
 */
class RecoveryStore(context: Context) {

    private val root = File(context.filesDir, "recovery").apply { mkdirs() }
    private val _items = MutableStateFlow(scan())
    val items: StateFlow<List<Snapshot>> = _items.asStateFlow()

    fun keyFor(id: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(id.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(20)
    }

    fun find(key: String): Snapshot? = _items.value.firstOrNull { it.key == key }

    /** [tmp] 를 사본으로 옮긴다 (복사 아님) */
    fun put(key: String, sourceUri: String?, name: String, tmp: File) {
        val dir = File(root, key)
        dir.deleteRecursively()
        dir.mkdirs()
        val target = File(dir, "doc." + (DocFormat.fromName(name)?.ext ?: "bin"))
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
        File(dir, "meta.json").writeText(
            JSONObject()
                .put("sourceUri", sourceUri ?: JSONObject.NULL)
                .put("name", name)
                .put("savedAt", System.currentTimeMillis())
                .toString()
        )
        _items.value = scan()
    }

    fun delete(key: String) {
        File(root, key).deleteRecursively()
        _items.value = scan()
    }

    private fun scan(): List<Snapshot> =
        (root.listFiles() ?: emptyArray()).mapNotNull { dir ->
            runCatching {
                val meta = JSONObject(File(dir, "meta.json").readText())
                val doc = dir.listFiles()?.firstOrNull { it.name.startsWith("doc.") } ?: return@mapNotNull null
                Snapshot(
                    key = dir.name,
                    sourceUri = if (meta.isNull("sourceUri")) null else meta.getString("sourceUri"),
                    name = meta.getString("name"),
                    savedAt = meta.getLong("savedAt"),
                    file = doc,
                )
            }.getOrNull()
        }.sortedByDescending { it.savedAt }
}
