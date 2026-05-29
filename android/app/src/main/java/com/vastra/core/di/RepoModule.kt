package com.vastra.core.di

import com.vastra.data.remote.VastraApiService
import com.vastra.data.repository.AuthRepository
import com.vastra.data.repository.FeedRepository
import com.vastra.data.repository.RecommendationRepository
import com.vastra.data.repository.WardrobeRepository
import com.vastra.core.storage.TokenStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object RepoModule {

    @Provides @Singleton
    fun provideAuthRepository(api: VastraApiService, tokenStore: TokenStore): AuthRepository =
        AuthRepository(api, tokenStore)

    @Provides @Singleton
    fun provideFeedRepository(api: VastraApiService): FeedRepository = FeedRepository(api)

    @Provides @Singleton
    fun provideWardrobeRepository(api: VastraApiService): WardrobeRepository = WardrobeRepository(api)

    @Provides @Singleton
    fun provideRecommendationRepository(api: VastraApiService): RecommendationRepository =
        RecommendationRepository(api)
}
