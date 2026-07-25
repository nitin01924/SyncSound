package com.syncsound.app.di

import android.content.Context
import com.syncsound.app.data.SessionRepository
import com.syncsound.app.network.NsdSessionDiscovery

/**
 * Composition root for long-lived services shared by all screens.
 */
class AppContainer(context: Context) {
    val repository = SessionRepository(
        context = context.applicationContext,
        discovery = NsdSessionDiscovery(context.applicationContext),
    )
}
