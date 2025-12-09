package org.example.demo.chat

import io.ktor.client.*
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.HttpTimeout
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.seconds

actual fun createHttpClient(): HttpClient {
    return HttpClient(Darwin) {
        // Устанавливаем увеличенные таймауты
        install(HttpTimeout) {
            connectTimeoutMillis = 60_000 // 60 секунд на установку соединения
            requestTimeoutMillis = 120_000 // 120 секунд на весь запрос (важно для AI запросов)
            socketTimeoutMillis = 120_000 // 120 секунд на чтение данных
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

