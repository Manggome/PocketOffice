package kr.neptune.pocketoffice.core

import android.content.Context
import android.net.ConnectivityManager
import android.os.StatFs
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kr.neptune.pocketoffice.BuildConfig
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * 편집기 엔진(OnlyOffice + 글꼴, 약 180MB)을 APK 와 따로 받아 둔다.
 *
 * APK 에 넣으면 앱을 고칠 때마다 업데이트가 200MB 가 넘는다. 엔진은 거의 바뀌지 않으므로
 * 별도 릴리스(engine-<id>)에 올려 두고 처음 한 번만 받는다. id 는 엔진 원본 커밋과
 * 추리는 스크립트의 해시라(app/build.gradle.kts), 둘 중 하나가 바뀔 때만 다시 받는다.
 *
 * 받은 zip 을 풀지 않고 그대로 읽는다. APK 안의 assets 와 같은 방식이고 저장 공간도 같다.
 */
object EngineStore {

    private const val TAG = "EngineStore"
    private const val REPO = "Manggome/PocketOffice"

    val id: String = BuildConfig.ENGINE_ID
    private val base = "https://github.com/$REPO/releases/download/engine-$id"

    sealed interface State {
        data object Missing : State
        data class Downloading(val done: Long, val total: Long) : State {
            val percent: Int get() = if (total > 0) (done * 100 / total).toInt() else 0
        }
        data object Verifying : State
        data object Ready : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Missing)
    val state: StateFlow<State> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    @Volatile
    private var zip: ZipFile? = null

    private lateinit var dir: File
    private val file get() = File(dir, "$id.zip")
    private val part get() = File(dir, "$id.zip.part")
    private val marker get() = File(dir, "$id.ok")

    fun init(context: Context) {
        dir = File(context.filesDir, "engine").apply { mkdirs() }
        if (file.exists() && marker.exists()) _state.value = State.Ready
    }

    /** 요청 하나에 답할 엔진 파일. 없으면 null */
    fun open(path: String): InputStream? {
        val z = zip ?: synchronized(this) {
            zip ?: runCatching { ZipFile(file) }.getOrNull()?.also { zip = it }
        } ?: return null
        val entry = z.getEntry(path) ?: return null
        return z.getInputStream(entry)
    }

    val isReady: Boolean get() = _state.value == State.Ready

    /** Wi-Fi 처럼 요금 걱정 없는 망인가. 처음 실행 때 자동으로 받을지 정한다 */
    fun onUnmeteredNetwork(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return cm.activeNetwork != null && !cm.isActiveNetworkMetered
    }

    /** 받기 시작. 이미 받는 중이면 아무것도 안 한다. 끊겼던 곳부터 이어 받는다 */
    fun download() {
        if (_state.value == State.Ready || job?.isActive == true) return
        job = scope.launch { runDownload() }
    }

    private fun runDownload() {
        try {
            val expected = fetchText("$base/engine.zip.sha256").trim().substringBefore(' ').lowercase()
            require(expected.length == 64) { "엔진 확인값이 이상합니다" }

            var conn = open("$base/engine.zip", part.length())
            var resumed = part.exists() && part.length() > 0 && conn.responseCode == 206
            if (!resumed && part.exists()) part.delete()
            if (conn.responseCode == 416) {
                // 이미 다 받아 둔 조각이다
                conn.disconnect()
                conn = open("$base/engine.zip", 0)
                resumed = false
                part.delete()
            }
            if (conn.responseCode !in 200..299) error("엔진 받기 실패 (HTTP ${conn.responseCode})")

            val already = if (resumed) part.length() else 0L
            val total = already + conn.contentLengthLong.coerceAtLeast(0)
            ensureSpace(total - already)

            _state.value = State.Downloading(already, total)
            conn.inputStream.use { input ->
                FileOutputStream(part, resumed).use { out ->
                    val buf = ByteArray(256 * 1024)
                    var done = already
                    var lastShown = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (done - lastShown > 512 * 1024) {
                            lastShown = done
                            _state.value = State.Downloading(done, total)
                        }
                    }
                }
            }
            conn.disconnect()

            _state.value = State.Verifying
            val actual = sha256(part)
            if (actual != expected) {
                part.delete()
                error("받은 엔진이 손상됐습니다. 다시 받아 주세요")
            }
            // 열리는지, 편집기가 들어 있는지까지 본다
            ZipFile(part).use { requireNotNull(it.getEntry("editor.html")) { "엔진 안에 편집기가 없습니다" } }

            file.delete()
            check(part.renameTo(file)) { "엔진 파일을 옮기지 못했습니다" }
            marker.writeText(actual)
            // 옛 엔진은 지운다
            dir.listFiles()?.filter { !it.name.startsWith(id) }?.forEach { it.delete() }
            _state.value = State.Ready
        } catch (t: Throwable) {
            Log.w(TAG, "엔진 받기 실패", t)
            _state.value = State.Failed(t.message ?: "엔진을 받지 못했습니다")
        }
    }

    private fun ensureSpace(need: Long) {
        val free = StatFs(dir.path).availableBytes
        if (need > 0 && free < need + 50L * 1024 * 1024) {
            error("저장 공간이 부족합니다. ${need / 1024 / 1024}MB 이상 비워 주세요")
        }
    }

    private fun open(url: String, from: Long): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 60000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "PocketOffice/" + BuildConfig.VERSION_NAME)
            if (from > 0) setRequestProperty("Range", "bytes=$from-")
        }

    private fun fetchText(url: String): String {
        val conn = open(url, 0)
        try {
            if (conn.responseCode !in 200..299) error("엔진 정보를 받지 못했습니다 (HTTP ${conn.responseCode})")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(1024 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
