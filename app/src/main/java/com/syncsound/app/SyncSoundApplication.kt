package com.syncsound.app

import android.app.Application
import com.syncsound.app.di.AppContainer
import com.syncsound.app.logging.CrashReporter

/**
 * Owns the process-wide dependency container. Keeping construction here makes
 * ViewModels testable without introducing a DI framework for the small MVP.
 */
class SyncSoundApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        CrashReporter.install()
    }
}
