package kr.neptune.pocketoffice.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class SortMode(val label: String) { RECENT("최근 수정순"), NAME("이름순") }

/** 문서를 누르면 어떻게 열지. 첫 화면 위쪽에서 고른다 */
enum class OpenMode(val label: String) {
    /** 툴바·메뉴 없이 보기만. PDF 는 앱 자체 뷰어 */
    VIEW("보기"),

    /** 지금까지의 편집 화면 */
    EDIT("편집"),
}

data class Settings(
    val openMode: OpenMode = OpenMode.VIEW,
    /** 앱을 켤 때 새 버전을 조용히 확인한다 */
    val autoUpdateCheck: Boolean = true,
    val sort: SortMode = SortMode.RECENT,
    /** PDF 뷰어에서 페이지 색을 뒤집어 어둡게 본다 */
    val pdfNight: Boolean = false,
    /**
     * 워드 보기 모드를 화면 해상도 그대로 그린다. 끄면(기본) 고해상도 화면에서 그리는 양을 줄여
     * 스크롤이 부드러워지는 대신 글자가 조금 덜 선명하다
     */
    val sharpWordView: Boolean = false,
)

class Prefs(context: Context) {

    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    fun update(block: (Settings) -> Settings) {
        val next = block(_settings.value)
        _settings.value = next
        sp.edit()
            .putString("openMode", next.openMode.name)
            .putBoolean("autoUpdateCheck", next.autoUpdateCheck)
            .putString("sort", next.sort.name)
            .putBoolean("pdfNight", next.pdfNight)
            .putBoolean("sharpWordView", next.sharpWordView)
            .apply()
    }

    private fun read() = Settings(
        openMode = runCatching { OpenMode.valueOf(sp.getString("openMode", null) ?: "") }.getOrDefault(OpenMode.VIEW),
        autoUpdateCheck = sp.getBoolean("autoUpdateCheck", true),
        sort = runCatching { SortMode.valueOf(sp.getString("sort", null) ?: "") }.getOrDefault(SortMode.RECENT),
        pdfNight = sp.getBoolean("pdfNight", false),
        sharpWordView = sp.getBoolean("sharpWordView", false),
    )
}
