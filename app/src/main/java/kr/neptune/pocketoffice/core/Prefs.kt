package kr.neptune.pocketoffice.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class SortMode(val label: String) { RECENT("최근 수정순"), NAME("이름순") }

data class Settings(
    /** 문서를 열면 읽기 모드로 시작한다. 실수로 고치는 일을 막고 싶은 사람용 */
    val openInViewMode: Boolean = false,
    /** 앱을 켤 때 새 버전을 조용히 확인한다 */
    val autoUpdateCheck: Boolean = true,
    val sort: SortMode = SortMode.RECENT,
)

class Prefs(context: Context) {

    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    fun update(block: (Settings) -> Settings) {
        val next = block(_settings.value)
        _settings.value = next
        sp.edit()
            .putBoolean("openInViewMode", next.openInViewMode)
            .putBoolean("autoUpdateCheck", next.autoUpdateCheck)
            .putString("sort", next.sort.name)
            .apply()
    }

    private fun read() = Settings(
        openInViewMode = sp.getBoolean("openInViewMode", false),
        autoUpdateCheck = sp.getBoolean("autoUpdateCheck", true),
        sort = runCatching { SortMode.valueOf(sp.getString("sort", null) ?: "") }.getOrDefault(SortMode.RECENT),
    )
}
