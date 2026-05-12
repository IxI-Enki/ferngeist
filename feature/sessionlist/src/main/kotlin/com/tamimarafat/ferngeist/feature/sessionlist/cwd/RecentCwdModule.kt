package com.tamimarafat.ferngeist.feature.sessionlist.cwd

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RecentCwdModule {
    @Binds
    @Singleton
    abstract fun bindRecentCwdStore(impl: DataStoreRecentCwdStore): RecentCwdStore
}
