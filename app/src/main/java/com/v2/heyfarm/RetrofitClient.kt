package com.v2.heyfarm

import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object RetrofitClient {
    const val BASE_URL = "https://devtoc-1.tailf71cde.ts.net"  // melon_kms via Tailscale serve (HTTPS). 이미지 URL 조합에도 사용.

    // 진단(비전+RAG+Gemini 2콜)·사진진단은 길게 걸려 OkHttp 기본 10초 read로는 timeout.
    // read/call을 넉넉히 늘림(LLM 지연 가변).
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .callTimeout(150, TimeUnit.SECONDS)
        .build()

    val api: HeyFarmApi by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(HeyFarmApi::class.java)
    }
}
