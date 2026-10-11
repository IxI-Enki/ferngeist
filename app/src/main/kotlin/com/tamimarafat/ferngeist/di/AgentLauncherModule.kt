package com.tamimarafat.ferngeist.di

import com.tamimarafat.ferngeist.acp.bridge.catalog.SessionCatalog
import com.tamimarafat.ferngeist.acp.bridge.hub.ChatConnectionHub
import com.tamimarafat.ferngeist.acp.bridge.launcher.AgentLauncher
import com.tamimarafat.ferngeist.core.model.repository.GatewaySourceRepository
import com.tamimarafat.ferngeist.core.model.repository.LaunchableTargetRepository
import com.tamimarafat.ferngeist.core.model.store.AuthEnvValueStore
import com.tamimarafat.ferngeist.gateway.GatewayRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Wires the server list's [AgentLauncher] to the shared hub and gateway clients.
 *
 * Kept out of [AppModule] so the server list's ACP seam is wired in one place.
 */
@Module
@InstallIn(SingletonComponent::class)
object AgentLauncherModule {
    /** The launcher that owns every verification transport the server list opens. */
    @Provides
    @Singleton
    fun provideAgentLauncher(
        hub: ChatConnectionHub,
        gatewayRepository: GatewayRepository,
        gatewaySourceRepository: GatewaySourceRepository,
        launchableTargetRepository: LaunchableTargetRepository,
        authEnvValueStore: AuthEnvValueStore,
        sessionCatalog: SessionCatalog,
    ): AgentLauncher =
        AgentLauncher(
            hub = hub,
            gatewayRepository = gatewayRepository,
            gatewaySourceRepository = gatewaySourceRepository,
            launchableTargetRepository = launchableTargetRepository,
            authEnvValueStore = authEnvValueStore,
            sessionCatalog = sessionCatalog,
        )
}
