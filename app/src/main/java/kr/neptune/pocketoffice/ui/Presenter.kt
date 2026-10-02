package kr.neptune.pocketoffice.ui

import android.app.Activity
import android.view.KeyEvent
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/** 쪽(슬라이드)을 넘기는 명령. 화면 터치·밀기·리모컨·키보드가 모두 이것으로 바뀐다 */
enum class PageCommand { NEXT, PREV, BLACK, EXIT, START }

/**
 * 블루투스 발표 리모컨과 키보드의 키를 넘기기 명령으로 바꾼다.
 *
 * 리모컨마다 보내는 키가 다르다. 로지텍 같은 발표용은 PageUp/PageDown, 싸구려는 화살표나
 * 엔터, 카메라 셔터용은 볼륨키를 보낸다. 흔한 것을 모두 받는다.
 * 볼륨키와 화면 검게/끝내기는 발표 중일 때만 받는다 (평소에는 원래 기능대로 둔다).
 */
object PresenterKeys {

    private val NEXT = setOf(
        KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
        KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_N, KeyEvent.KEYCODE_MOVE_END,
    )
    private val PREV = setOf(
        KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP,
        KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_P,
    )

    fun map(keyCode: Int, presenting: Boolean): PageCommand? = when {
        keyCode in NEXT -> PageCommand.NEXT
        keyCode in PREV -> PageCommand.PREV
        // 발표 리모컨의 "발표 시작" 버튼은 F5 (이어서 하기는 Shift+F5)
        keyCode == KeyEvent.KEYCODE_F5 && !presenting -> PageCommand.START
        !presenting -> null
        keyCode == KeyEvent.KEYCODE_VOLUME_DOWN -> PageCommand.NEXT
        keyCode == KeyEvent.KEYCODE_VOLUME_UP -> PageCommand.PREV
        // 발표 리모컨의 "화면 끄기" 버튼은 B 나 마침표를 보낸다
        keyCode == KeyEvent.KEYCODE_B || keyCode == KeyEvent.KEYCODE_PERIOD -> PageCommand.BLACK
        keyCode == KeyEvent.KEYCODE_ESCAPE || keyCode == KeyEvent.KEYCODE_F5 -> PageCommand.EXIT
        else -> null
    }

    /**
     * 액티비티의 dispatchKeyEvent 에서 부른다. 처리했으면 true (눌림과 뗌 모두 삼켜서
     * 볼륨이 같이 바뀌거나 WebView 가 따로 스크롤하지 않게).
     * 누르고 있으면 반복해서 오는 것은 무시한다 — 한 번 누름 = 한 쪽.
     */
    fun handle(event: KeyEvent, presenting: Boolean, onCommand: (PageCommand) -> Unit): Boolean {
        val cmd = map(event.keyCode, presenting) ?: return false
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) onCommand(cmd)
        return true
    }
}

/** 발표 중: 상태바·내비게이션 바를 숨기고(가장자리를 밀면 잠깐 나온다) 화면이 꺼지지 않게 */
object Immersive {
    fun set(activity: Activity, on: Boolean) {
        val window = activity.window
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (on) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}
