package com.omnidocs.app

import android.app.Application
import com.omnidocs.app.agent.WorkManagerCoordinator
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class NotesApplication : Application() {

    @Inject
    lateinit var workManagerCoordinator: WorkManagerCoordinator

    override fun onCreate() {
        super.onCreate()
        workManagerCoordinator.schedulePeriodicDailyHealthScan()
    }
}
