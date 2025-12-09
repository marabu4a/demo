package org.example.demo.chat

import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

actual fun createHttpClient(): HttpClient {
    // Создаем TrustManager, который принимает все сертификаты
    val trustAllCerts = arrayOf<TrustManager>(
        object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
    )
    
    // Создаем SSL контекст
    val sslContext = SSLContext.getInstance("SSL")
    sslContext.init(null, trustAllCerts, java.security.SecureRandom())
    
    // Настраиваем OkHttp клиент с увеличенными таймаутами
    val okHttpClient = OkHttpClient.Builder()
        .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
        .hostnameVerifier { _, _ -> true }
        .connectTimeout(60, java.util.concurrent.TimeUnit.SECONDS) // Таймаут на установку соединения: 60 секунд
        .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS) // Таймаут на чтение ответа: 120 секунд (важно для AI запросов)
        .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS) // Таймаут на отправку запроса: 60 секунд
        .build()
    
    return HttpClient(OkHttp) {
        engine {
            preconfigured = okHttpClient
        }
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
                encodeDefaults = false
            })
        }
    }
}

