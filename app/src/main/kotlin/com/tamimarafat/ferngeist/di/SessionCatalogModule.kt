package com.tamimarafat.ferngeist.di

import com.tamimarafat.ferngeist.acp.bridge.catalog.SessionCatalog
import com.tamimarafat.ferngeist.acp.bridge.connection.ConnectivityObserver
import com.tamimarafat.ferngeist.acp.bridge.hub.ChatConnectionHub
import com.tamimarafat.ferngeist.core.model.repository.GatewaySourceRepository
import com.tamimarafat.ferngeist.core.model.repository.LaunchableTargetRepository
import com.tamimarafat.ferngeist.core.model.repository.SessionRepository
import com.tamimarafat.ferngeist.core.model.store.AuthEnvValueStore
import com.tamimarafat.ferngeist.gateway.GatewayRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import javax.inject.Singleton

/**
 * Wires the session list's [SessionCatalog] to the shared hub, gateway client
 * and session table.
 *
 * Kept out of [AppModule] so the session list's ACP seam is wired in one place.
 */
@Module
@InstallIn(SingletonComponent::class)
object SessionCatalogModule {
    /** The catalog that owns every listing transport and session-table listing write. */
    @Provides
    @Singleton
    @Suppress("LongParameterList")
    fun provideSessionCatalog(
        hub: ChatConnectionHub,
        gatewayRepository: GatewayRepository,
        gatewaySourceRepository: GatewaySourceRepository,
        launchableTargetRepository: LaunchableTargetRepository,
        sessionRepository: SessionRepository,
        connectivityObserver: ConnectivityObserver,
        authEnvValueStore: AuthEnvValueStore,
        applicationScope: CoroutineScope,
    ): SessionCatalog =
        SessionCatalog(
            hub = hub,
            gatewayRepository = gatewayRepository,
            gatewaySourceRepository = gatewaySourceRepository,
            launchableTargetRepository = launchableTargetRepository,
            sessionRepository = sessionRepository,
            connectivityObserver = connectivityObserver,
            authEnvValueStore = authEnvValueStore,
            scope = applicationScope,
        )
}
