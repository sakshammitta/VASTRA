package com.vastra.core.network

import com.vastra.BuildConfig
import com.vastra.core.storage.TokenStore
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

class AuthInterceptor(private val tokenStore: TokenStore) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
        val token = tokenStore.getToken()
        val original = chain.request()
        val request = if (token != null) {
            original.newBuilder()
                .addHeader("Authorization", "Bearer $token")
                .build()
        } else original
        // Safe debug: never log the token itself — only whether it's attached
        // and its length, so we can confirm the scan upload is authenticated.
        android.util.Log.d(
            "VastraScan",
            "${original.method} ${original.url.encodedPath} → Authorization attached=${token != null} tokenLen=${token?.length ?: 0}"
        )
        return chain.proceed(request)
    }
}

object ApiClient {
    fun buildOkHttp(tokenStore: TokenStore): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BODY
                    else HttpLoggingInterceptor.Level.NONE
        }
        return OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(tokenStore))
            .addInterceptor(logging)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    fun buildRetrofit(okHttpClient: OkHttpClient): Retrofit =
        Retrofit.Builder()
            .baseUrl(BuildConfig.BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
}
