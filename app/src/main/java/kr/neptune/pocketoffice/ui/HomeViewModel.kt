package kr.neptune.pocketoffice.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kr.neptune.pocketoffice.PocketOfficeApp
import kr.neptune.pocketoffice.core.AppUpdater
import kr.neptune.pocketoffice.core.DeviceDoc
import kr.neptune.pocketoffice.core.DeviceScanner
import kr.neptune.pocketoffice.core.DocKind
import kr.neptune.pocketoffice.core.EngineStore
import kr.neptune.pocketoffice.core.RecentDoc
import kr.neptune.pocketoffice.core.SortMode

enum class HomeTab(val label: String) { RECENT("최근"), DEVICE("내 폰") }

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as PocketOfficeApp
    val prefs = app.prefs
    val recent = app.recent
    val recovery = app.recovery

    val tab = MutableStateFlow(HomeTab.RECENT)
    val filter = MutableStateFlow<DocKind?>(null)
    val query = MutableStateFlow("")

    private val _hasAccess = MutableStateFlow(DeviceScanner.hasAccess(application))
    val hasAccess: StateFlow<Boolean> = _hasAccess.asStateFlow()

    private val _device = MutableStateFlow<List<DeviceDoc>>(emptyList())
    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private var scannedOnce = false

    val recentShown: StateFlow<List<RecentDoc>> =
        combine(recent.items, filter, query) { items, kind, q ->
            items.filter { (kind == null || it.format?.kind == kind) && matches(it.name, q) }
                // 즐겨찾기를 위로
                .sortedByDescending { it.starred }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val deviceShown: StateFlow<List<DeviceDoc>> =
        combine(_device, filter, query, prefs.settings) { items, kind, q, settings ->
            val list = items.filter { (kind == null || it.format?.kind == kind) && matches(it.name, q) }
            when (settings.sort) {
                SortMode.RECENT -> list.sortedByDescending { it.modified }
                SortMode.NAME -> list.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        // 처음 켰을 때 Wi-Fi 면 편집 엔진을 바로 받기 시작한다. 데이터 요금이 드는 망이면 버튼을 기다린다
        if (!EngineStore.isReady && EngineStore.onUnmeteredNetwork(application)) EngineStore.download()
        if (prefs.settings.value.autoUpdateCheck) {
            viewModelScope.launch { AppUpdater.check(silent = true) }
        }
    }

    /** 화면에 돌아올 때마다. 설정에서 권한을 켜고 돌아온 경우를 잡는다 */
    fun onResume() {
        val access = DeviceScanner.hasAccess(getApplication())
        val changed = access != _hasAccess.value
        _hasAccess.value = access
        if (access && (changed || !scannedOnce || tab.value == HomeTab.DEVICE)) rescan()
    }

    fun rescan() {
        if (_scanning.value) return
        viewModelScope.launch {
            _scanning.value = true
            _device.value = DeviceScanner.scan(getApplication())
            scannedOnce = true
            _scanning.value = false
        }
    }

    private fun matches(name: String, q: String) = q.isBlank() || name.contains(q.trim(), ignoreCase = true)
}
