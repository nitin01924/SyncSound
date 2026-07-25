package com.syncsound.app.logging

/**
 * Records otherwise-unhandled failures before delegating to Android's normal
 * crash handler. It is diagnostic, not an attempt to continue corrupted state.
 */
object CrashReporter {
    fun install() {
        val delegate = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            AppLog.e(
                LogArea.CRASH,
                "uncaught_exception",
                error,
                "thread" to thread.name,
            )
            delegate?.uncaughtException(thread, error)
        }
    }
}
