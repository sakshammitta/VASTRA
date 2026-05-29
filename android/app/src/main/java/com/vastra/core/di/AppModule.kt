package com.vastra.core.di

import com.vastra.core.network.ApiClient
import com.vastra.core.storage.TokenStore
import com.vastra.data.remote.VastraApiService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides @Singleton
    fun provideOkHttp(tokenStore: TokenStore): OkHttpClient =
        ApiClient.buildOkHttp(tokenStore)

    @Provides @Singleton
    fun provideRetrofit(okHttpClient: OkHttpClient): Retrofit =
        ApiClient.buildRetrofit(okHttpClient)

    @Provides @Singleton
    fun provideApiService(retrofit: Retrofit): VastraApiService =
        retrofit.create(VastraApiService::class.java)
}

@Module
@InstallIn(SingletonComponent::class)
object CoilModule {
    @Provides @Singleton
    fun provideCoilImageLoader(
        @dagger.hilt.android.qualifiers.ApplicationContext context: android.content.Context,
        okHttpClient: OkHttpClient
    ): coil.ImageLoader = coil.ImageLoader.Builder(context)
        .okHttpClient(okHttpClient)
        .crossfade(true)
        .memoryCachePolicy(coil.request.CachePolicy.ENABLED)
        .diskCachePolicy(coil.request.CachePolicy.ENABLED)
        .build()
}
