package com.lumina.tv.data

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

internal object UpdateCheckHttp {
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(8, TimeUnit.SECONDS)
            .build()
    }
}
