package kr.neptune.pocketoffice

import android.app.Application
import kr.neptune.pocketoffice.core.EngineStore
import kr.neptune.pocketoffice.core.Prefs
import kr.neptune.pocketoffice.core.RecentStore
import kr.neptune.pocketoffice.core.RecoveryStore

class PocketOfficeApp : Application() {

    lateinit var prefs: Prefs
        private set
    lateinit var recent: RecentStore
        private set
    lateinit var recovery: RecoveryStore
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = Prefs(this)
        recent = RecentStore(this)
        recovery = RecoveryStore(this)
        EngineStore.init(this)
    }

    companion object {
        lateinit var instance: PocketOfficeApp
            private set
    }
}
