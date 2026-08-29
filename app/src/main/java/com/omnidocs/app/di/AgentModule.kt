package com.omnidocs.app.di

import com.omnidocs.app.agent.DefaultPolicyGuard
import com.omnidocs.app.agent.PolicyGuard
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AgentModule {

    @Binds
    @Singleton
    abstract fun bindPolicyGuard(impl: DefaultPolicyGuard): PolicyGuard
}
