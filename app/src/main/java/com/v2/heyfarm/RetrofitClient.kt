package com.v2.heyfarm

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object RetrofitClient {
    private const val BASE_URL = "https://devtoc-1.tailf71cde.ts.net"  // melon_kms via Tailscale serve (HTTPS)

    val api: HeyFarmApi by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(HeyFarmApi::class.java)
    }
}
