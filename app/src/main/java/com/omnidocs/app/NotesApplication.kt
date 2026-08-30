package com.omnidocs.app

import android.app.Application
import android.content.ComponentCallbacks2
import com.omnidocs.app.agent.WorkManagerCoordinator
import com.omnidocs.app.ai.NativeMemoryManager
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class NotesApplication : Application() {

    @Inject
    lateinit var workManagerCoordinator: WorkManagerCoordinator

    @Inject
    lateinit var nativeMemoryManager: NativeMemoryManager

    override fun onCreate() {
        super.onCreate()
        workManagerCoordinator.schedulePeriodicDailyHealthScan()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        nativeMemoryManager.onTrimMemory(level)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        nativeMemoryManager.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
    }
}
