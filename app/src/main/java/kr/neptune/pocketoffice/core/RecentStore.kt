package kr.neptune.pocketoffice.core

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** 최근에 연 문서 한 건 */
data class RecentDoc(
    val uri: String,
    val name: String,
    val size: Long,
    val lastOpened: Long,
    val starred: Boolean = false,
) {
    val format: DocFormat? get() = DocFormat.fromName(name)
    val parsedUri: Uri get() = Uri.parse(uri)
}

/**
 * 최근 문서 목록. 수십 건이라 SharedPreferences 에 JSON 한 덩어리로 둔다.
 * 원본 파일은 건드리지 않는다 — 목록에서 빼도 폰의 파일은 그대로다.
 */
class RecentStore(context: Context) {

    private val prefs = context.getSharedPreferences("recent", Context.MODE_PRIVATE)
    private val _items = MutableStateFlow(load())
    val items: StateFlow<List<RecentDoc>> = _items.asStateFlow()

    fun touch(uri: Uri, name: String, size: Long) {
        val key = uri.toString()
        val old = _items.value.firstOrNull { it.uri == key }
        val entry = RecentDoc(key, name, size, System.currentTimeMillis(), old?.starred ?: false)
        save(listOf(entry) + _items.value.filter { it.uri != key })
    }

    /** 다른 이름으로 저장한 뒤처럼 같은 문서가 다른 uri 로 바뀌었을 때 */
    fun replace(old: Uri?, uri: Uri, name: String, size: Long) {
        val starred = _items.value.firstOrNull { it.uri == old?.toString() }?.starred ?: false
        val rest = _items.value.filter { it.uri != old?.toString() && it.uri != uri.toString() }
        save(listOf(RecentDoc(uri.toString(), name, size, System.currentTimeMillis(), starred)) + rest)
    }

    fun remove(uri: String) = save(_items.value.filter { it.uri != uri })

    fun setStarred(uri: String, starred: Boolean) =
        save(_items.value.map { if (it.uri == uri) it.copy(starred = starred) else it })

    fun clear() = save(_items.value.filter { it.starred })

    private fun save(list: List<RecentDoc>) {
        // 즐겨찾기는 개수 제한에서 빼 준다
        val (starred, rest) = list.partition { it.starred }
        val trimmed = starred + rest.take(MAX)
        _items.value = trimmed.sortedByDescending { it.lastOpened }
        val arr = JSONArray()
        _items.value.forEach {
            arr.put(
                JSONObject()
                    .put("uri", it.uri)
                    .put("name", it.name)
                    .put("size", it.size)
                    .put("lastOpened", it.lastOpened)
                    .put("starred", it.starred)
            )
        }
        prefs.edit().putString("items", arr.toString()).apply()
    }

    private fun load(): List<RecentDoc> = runCatching {
        val arr = JSONArray(prefs.getString("items", "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            RecentDoc(
                uri = o.getString("uri"),
                name = o.optString("name", "문서"),
                size = o.optLong("size", -1),
                lastOpened = o.optLong("lastOpened", 0),
                starred = o.optBoolean("starred", false),
            )
        }
    }.getOrDefault(emptyList())

    private companion object {
        const val MAX = 60
    }
}
