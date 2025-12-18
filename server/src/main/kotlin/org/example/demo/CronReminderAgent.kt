package org.example.demo

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.util.concurrent.TimeUnit

/**
 * Cron-агент для напоминаний
 * Предназначен для запуска через cron/scheduled tasks
 * Выполняет одну проверку и завершает работу
 */
class CronReminderAgent(
    private val mcpServerUrl: String = "http://localhost:8080/mcp",
    private val mode: CronMode = CronMode.CHECK_DUE // Режим работы
) {
    companion object {
        // GigaChat Authorization Key (Base64)
        private const val AI_API_KEY = "MDE5YWRhYTktNmIxZi03M2QyLWIzODctOTQ4NWIzOTdhNTVmOjI0MGY0MzcxLTc2ZWYtNGMzMC04YTk5LTFkYjA1ZjgwNWQ1NQ=="
    }
    
    private val httpClient = createHttpClientWithSsl()
    
    /**
     * Создает HttpClient с настроенным SSL для работы с GigaChat API
     */
    private fun createHttpClientWithSsl(): HttpClient {
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
            .hostnameVerifier { hostname: String, session: javax.net.ssl.SSLSession -> true }
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
        
        return HttpClient(OkHttp) {
            engine {
                preconfigured = okHttpClient
            }
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    prettyPrint = true
                    isLenient = true
                })
            }
        }
    }
    
    enum class CronMode {
        CHECK_DUE,      // Проверка просроченных напоминаний
        FULL_SUMMARY    // Полная сводка
    }
    
    /**
     * Выполняет одну проверку и завершает работу
     */
    suspend fun run() {
        try {
            when (mode) {
                CronMode.CHECK_DUE -> checkDueReminders()
                CronMode.FULL_SUMMARY -> getFullSummaryWithAi()
            }
        } finally {
            httpClient.close()
        }
    }
    
    /**
     * Проверяет просроченные напоминания
     */
    private suspend fun checkDueReminders() {
        try {
            val result = callMcpTool("reminder", buildJsonObject {
                put("action", "get_due")
            })
            
            if (result != null && !result.isError) {
                val content = result.content.firstOrNull()?.text ?: ""
                if (content.isNotEmpty() && !content.contains("Нет просроченных")) {
                    // Отправляем в AI для анализа
                    val aiResponse = analyzeWithAi(
                        prompt = """
                            У тебя есть список просроченных напоминаний. 
                            Проанализируй их и создай краткое, но информативное сообщение для пользователя.
                            Выдели самые важные и срочные задачи.
                            Будь дружелюбным и мотивирующим.
                            
                            Данные:
                            $content
                        """.trimIndent()
                    )
                    
                    val message = aiResponse ?: content
                    val shortMessage = message.lines().take(3).joinToString(" ").take(200)
                    
                    // Выводим в консоль
                    println("🔔 ПРОСРОЧЕННЫЕ НАПОМИНАНИЯ:")
                    println(message)
                    
                    // Отправляем системное уведомление macOS
                    sendMacOSNotification(
                        title = "Просроченные напоминания",
                        subtitle = "Требуют внимания",
                        message = shortMessage
                    )
                } else {
                    println("✅ Нет просроченных напоминаний")
                }
            }
        } catch (e: Exception) {
            println("⚠️ Ошибка при проверке просроченных напоминаний: ${e.message}")
            e.printStackTrace()
        }
    }
    
    /**
     * Получает полную сводку по напоминаниям
     */
    private suspend fun getFullSummaryWithAi() {
        try {
            val result = callMcpTool("reminder", buildJsonObject {
                put("action", "get_summary")
            })
            
            if (result != null && !result.isError) {
                val summary = result.content.firstOrNull()?.text ?: ""
                if (summary.isNotEmpty()) {
                    // Отправляем в AI для анализа
                    val aiResponse = analyzeWithAi(
                        prompt = """
                            Ты - персональный ассистент по управлению напоминаниями.
                            У тебя есть сводка по всем напоминаниям пользователя.
                            
                            Задача:
                            1. Проанализируй данные
                            2. Создай красивую, структурированную сводку
                            3. Выдели важные моменты (просроченные, срочные, приоритетные)
                            4. Дай рекомендации, если нужно
                            5. Будь дружелюбным и мотивирующим
                            
                            Данные:
                            $summary
                            
                            Создай сводку в формате:
                            - Краткое вступление
                            - Статистика (кратко)
                            - Важные моменты (если есть)
                            - Рекомендации (если нужно)
                            - Мотивирующее заключение
                        """.trimIndent()
                    )
                    
                    val message = aiResponse ?: summary
                    val shortMessage = message.lines()
                        .filter { it.isNotBlank() && !it.startsWith("=") }
                        .take(5)
                        .joinToString(" ")
                        .take(200)
                    
                    // Выводим в консоль
                    println("=".repeat(60))
                    println("📋 СВОДКА ПО НАПОМИНАНИЯМ")
                    println("   Время: ${java.time.Instant.now()}")
                    println("=".repeat(60))
                    println(message)
                    println("=".repeat(60))
                    
                    // Отправляем системное уведомление macOS
                    sendMacOSNotification(
                        title = "Сводка по напоминаниям",
                        subtitle = "AI анализ",
                        message = shortMessage
                    )
                }
            }
        } catch (e: Exception) {
            println("⚠️ Ошибка при получении сводки: ${e.message}")
            e.printStackTrace()
        }
    }
    
    /**
     * Отправляет системное уведомление macOS
     */
    private fun sendMacOSNotification(title: String, message: String, subtitle: String? = null) {
        try {
            val script = buildString {
                append("display notification \"")
                append(message.replace("\"", "\\\"").replace("\n", " "))
                append("\"")
                
                if (title.isNotEmpty()) {
                    append(" with title \"")
                    append(title.replace("\"", "\\\""))
                    append("\"")
                }
                
                if (subtitle != null && subtitle.isNotEmpty()) {
                    append(" subtitle \"")
                    append(subtitle.replace("\"", "\\\""))
                    append("\"")
                }
            }
            
            val process = ProcessBuilder("osascript", "-e", script)
                .redirectErrorStream(true)
                .start()
            
            process.waitFor()
        } catch (e: Exception) {
            println("⚠️ Не удалось отправить системное уведомление: ${e.message}")
        }
    }
    
    /**
     * Отправляет данные в AI для анализа
     */
    private suspend fun analyzeWithAi(prompt: String): String? {
        return try {
            val accessToken = getGigaChatToken(AI_API_KEY)
            if (accessToken == null) {
                println("⚠️ Не удалось получить токен доступа для GigaChat")
                return null
            }
            
            val requestBody = buildJsonObject {
                put("model", "GigaChat")
                putJsonArray("messages") {
                    addJsonObject {
                        put("role", "user")
                        put("content", prompt)
                    }
                }
                put("temperature", 0.7)
                put("stream", false)
            }
            
            val response = httpClient.post("https://gigachat.devices.sberbank.ru/api/v1/chat/completions") {
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer $accessToken")
                setBody(requestBody.toString())
            }
            
            val responseBody = response.body<String>()
            val json = Json { ignoreUnknownKeys = true }
            val responseJson = json.parseToJsonElement(responseBody).jsonObject
            
            if (responseJson.containsKey("error")) {
                val error = responseJson["error"]?.jsonObject
                println("❌ Ошибка AI API: ${error?.get("message")?.jsonPrimitive?.content}")
                return null
            }
            
            val choices = responseJson["choices"]?.jsonArray
            val message = choices?.firstOrNull()?.jsonObject?.get("message")?.jsonObject
            val content = message?.get("content")?.jsonPrimitive?.content
            
            content
        } catch (e: Exception) {
            println("⚠️ Ошибка при обращении к AI: ${e.message}")
            null
        }
    }
    
    /**
     * Получает токен доступа для GigaChat
     */
    private suspend fun getGigaChatToken(apiKey: String): String? {
        return try {
            val tokenUrl = "https://ngw.devices.sberbank.ru:9443/api/v2/oauth"
            
            val response = httpClient.post(tokenUrl) {
                contentType(ContentType.Application.Json)
                header("Authorization", "Basic $apiKey")
                header("RqUID", java.util.UUID.randomUUID().toString())
                header("Content-Type", "application/x-www-form-urlencoded")
                setBody("scope=GIGACHAT_API_PERS")
            }
            
            val responseBody = response.body<String>()
            val json = Json { ignoreUnknownKeys = true }
            val responseJson = json.parseToJsonElement(responseBody).jsonObject
            
            responseJson["access_token"]?.jsonPrimitive?.content
        } catch (e: Exception) {
            println("⚠️ Ошибка при получении токена GigaChat: ${e.message}")
            null
        }
    }
    
    /**
     * Вызывает MCP инструмент
     */
    private suspend fun callMcpTool(toolName: String, arguments: JsonObject): McpToolResult? {
        return try {
            val requestBody = buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", System.currentTimeMillis().toInt())
                put("method", "tools/call")
                putJsonObject("params") {
                    put("name", toolName)
                    put("arguments", arguments)
                }
            }
            
            val response = httpClient.post(mcpServerUrl) {
                contentType(ContentType.Application.Json)
                setBody(requestBody.toString())
            }
            
            val responseBody = response.body<String>()
            val json = Json { ignoreUnknownKeys = true }
            val responseJson = json.parseToJsonElement(responseBody).jsonObject
            
            if (responseJson.containsKey("error")) {
                val error = responseJson["error"]?.jsonObject
                println("❌ Ошибка MCP: ${error?.get("message")?.jsonPrimitive?.content}")
                return null
            }
            
            val result = responseJson["result"]?.jsonObject
            if (result != null) {
                val content = result["content"]?.jsonArray
                val isError = result["isError"]?.jsonPrimitive?.boolean ?: false
                
                McpToolResult(
                    content = content?.mapNotNull { 
                        it.jsonObject["text"]?.jsonPrimitive?.content 
                    }?.map { McpContentItem(it) } ?: emptyList(),
                    isError = isError
                )
            } else {
                null
            }
        } catch (e: Exception) {
            println("❌ Ошибка при вызове MCP инструмента: ${e.message}")
            null
        }
    }
}

/**
 * Запускает cron-агента напоминаний
 * 
 * Использование:
 *   java -jar cron-reminder-agent.jar check_due    - проверка просроченных
 *   java -jar cron-reminder-agent.jar summary     - полная сводка
 */
fun main(args: Array<String>) {
    val mode = when (args.getOrNull(0)?.lowercase()) {
        "summary", "full", "full_summary" -> CronReminderAgent.CronMode.FULL_SUMMARY
        "check", "check_due", "due" -> CronReminderAgent.CronMode.CHECK_DUE
        else -> CronReminderAgent.CronMode.CHECK_DUE
    }
    
    val mcpServerUrl = args.getOrNull(1) ?: "http://localhost:8080/mcp"
    
    println("🤖 Cron-агент напоминаний")
    println("   Режим: ${mode.name}")
    println("   MCP сервер: $mcpServerUrl")
    println()
    
    val agent = CronReminderAgent(
        mcpServerUrl = mcpServerUrl,
        mode = mode
    )
    
    runBlocking {
        agent.run()
    }
    
    println("✅ Проверка завершена")
}

