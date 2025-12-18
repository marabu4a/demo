package org.example.demo

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * AI-агент для периодической проверки напоминаний и выдачи сводки через AI
 * Использует AI для анализа и форматирования сводки по напоминаниям
 */
class AiReminderAgent(
    private val mcpServerUrl: String = "http://localhost:8080/mcp",
    private val aiApiUrl: String = "https://gigachat.devices.sberbank.ru/api/v1/chat/completions",
    private val checkIntervalMinutes: Long = 60, // Проверка каждый час
    private val summaryIntervalHours: Double = 6.0 // Полная сводка каждые 6 часов (может быть дробным для тестирования)
) {
    companion object {
        // GigaChat Authorization Key (Base64) - используется для получения access token
        private const val AI_API_KEY = "MDE5YWRhYTktNmIxZi03M2QyLWIzODctOTQ4NWIzOTdhNTVmOjI0MGY0MzcxLTc2ZWYtNGMzMC04YTk5LTFkYjA1ZjgwNWQ1NQ=="
    }
    
    /**
     * Отправляет системное уведомление macOS
     * @param title Заголовок уведомления
     * @param message Текст сообщения
     * @param subtitle Подзаголовок (опционально)
     */
    private fun sendMacOSNotification(title: String, message: String, subtitle: String? = null) {
        try {
            // Формируем AppleScript команду для системного уведомления
            val script = buildString {
                append("display notification \"")
                // Экранируем кавычки и заменяем переносы строк на пробелы
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
            
            // Выполняем команду через osascript
            val process = ProcessBuilder("osascript", "-e", script)
                .redirectErrorStream(true)
                .start()
            
            process.waitFor()
        } catch (e: Exception) {
            // Если системные уведомления недоступны, просто логируем ошибку
            // Не прерываем работу агента из-за проблем с уведомлениями
            println("⚠️ Не удалось отправить системное уведомление: ${e.message}")
        }
    }
    private val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                prettyPrint = true
            })
        }
    }
    
    private var isRunning = false
    private var agentJob: Job? = null
    
    /**
     * Запускает агента в фоновом режиме
     */
    fun start() {
        if (isRunning) {
            println("⚠️ AI-агент уже запущен")
            return
        }
        
        isRunning = true
        println("🚀 Запуск AI-агента напоминаний...")
        println("   URL MCP сервера: $mcpServerUrl")
        println("   URL AI API: $aiApiUrl")
        println("   Интервал проверки: $checkIntervalMinutes минут")
        println("   Интервал сводки: $summaryIntervalHours часов")
        println()
        
        // Ключ захардкожен в классе, проверка не нужна
        
        agentJob = CoroutineScope(Dispatchers.Default).launch {
            var lastSummaryTime = System.currentTimeMillis()
            
            // Для тестирования: если интервал очень маленький, выдаем сводку сразу
            if (checkIntervalMinutes <= 5) {
                println("🧪 ТЕСТОВЫЙ РЕЖИМ: Первая проверка через ${checkIntervalMinutes} минут(ы)")
            }
            
            while (isRunning) {
                try {
                    val now = System.currentTimeMillis()
                    val timeSinceLastSummary = (now - lastSummaryTime) / (1000.0 * 60 * 60) // в часах (дробное)
                    
                    // Проверяем просроченные напоминания
                    checkDueReminders()
                    
                    // Если прошло достаточно времени, выдаем полную сводку через AI
                    if (timeSinceLastSummary >= summaryIntervalHours) {
                        getFullSummaryWithAi()
                        lastSummaryTime = now
                    }
                    
                    // Ждем до следующей проверки
                    delay(checkIntervalMinutes * 60 * 1000)
                } catch (e: Exception) {
                    println("❌ Ошибка в AI-агенте: ${e.message}")
                    e.printStackTrace()
                    // Продолжаем работу даже при ошибке
                    delay(checkIntervalMinutes * 60 * 1000)
                }
            }
        }
        
        println("✅ AI-агент запущен и работает в фоновом режиме")
        println("   Нажмите Ctrl+C для остановки")
    }
    
    /**
     * Останавливает агента
     */
    fun stop() {
        if (!isRunning) {
            println("⚠️ AI-агент не запущен")
            return
        }
        
        println("🛑 Остановка AI-агента...")
        isRunning = false
        agentJob?.cancel()
        httpClient.close()
        println("✅ AI-агент остановлен")
    }
    
    /**
     * Проверяет просроченные напоминания и отправляет их в AI для анализа
     */
    private suspend fun checkDueReminders() {
        try {
            val result = callMcpTool("reminder", buildJsonObject {
                put("action", "get_due")
            })
            
            if (result != null && !result.isError) {
                val content = result.content.firstOrNull()?.text ?: ""
                if (content.isNotEmpty() && !content.contains("Нет просроченных")) {
                    // Отправляем в AI для анализа и форматирования
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
                    
                    if (aiResponse != null) {
                        println("🔔 ПРОСРОЧЕННЫЕ НАПОМИНАНИЯ (AI анализ):")
                        println(aiResponse)
                        println()
                        
                        // Отправляем системное уведомление macOS
                        val shortMessage = aiResponse.lines().take(3).joinToString(" ").take(200)
                        sendMacOSNotification(
                            title = "Просроченные напоминания",
                            subtitle = "Требуют внимания",
                            message = shortMessage
                        )
                    } else {
                        // Если AI недоступен, выводим сырые данные
                        println("🔔 ПРОСРОЧЕННЫЕ НАПОМИНАНИЯ:")
                        println(content)
                        println()
                        
                        // Отправляем системное уведомление с сырыми данными
                        val shortMessage = content.lines().take(3).joinToString(" ").take(200)
                        sendMacOSNotification(
                            title = "Просроченные напоминания",
                            subtitle = "Требуют внимания",
                            message = shortMessage
                        )
                    }
                }
            }
        } catch (e: Exception) {
            println("⚠️ Ошибка при проверке просроченных напоминаний: ${e.message}")
        }
    }
    
    /**
     * Получает полную сводку по напоминаниям и отправляет в AI для анализа
     */
    private suspend fun getFullSummaryWithAi() {
        try {
            val result = callMcpTool("reminder", buildJsonObject {
                put("action", "get_summary")
            })
            
            if (result != null && !result.isError) {
                val summary = result.content.firstOrNull()?.text ?: ""
                if (summary.isNotEmpty()) {
                    // Отправляем в AI для анализа и создания красивой сводки
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
                    
                    if (aiResponse != null) {
                        println("=".repeat(60))
                        println("📋 СВОДКА ПО НАПОМИНАНИЯМ (AI анализ)")
                        println("   Время: ${java.time.Instant.now()}")
                        println("=".repeat(60))
                        println()
                        println(aiResponse)
                        println("=".repeat(60))
                        println()
                        
                        // Отправляем системное уведомление macOS
                        val shortMessage = aiResponse.lines()
                            .filter { it.isNotBlank() && !it.startsWith("=") }
                            .take(5)
                            .joinToString(" ")
                            .take(200)
                        sendMacOSNotification(
                            title = "Сводка по напоминаниям",
                            subtitle = "AI анализ",
                            message = shortMessage
                        )
                    } else {
                        // Если AI недоступен, выводим сырые данные
                        println("=".repeat(60))
                        println("📋 СВОДКА ПО НАПОМИНАНИЯМ")
                        println("   Время: ${java.time.Instant.now()}")
                        println("=".repeat(60))
                        println()
                        println(summary)
                        println("=".repeat(60))
                        println()
                        
                        // Отправляем системное уведомление с сырыми данными
                        val shortMessage = summary.lines()
                            .filter { it.isNotBlank() && !it.startsWith("=") }
                            .take(5)
                            .joinToString(" ")
                            .take(200)
                        sendMacOSNotification(
                            title = "Сводка по напоминаниям",
                            subtitle = "Статистика",
                            message = shortMessage
                        )
                    }
                }
            }
        } catch (e: Exception) {
            println("⚠️ Ошибка при получении сводки: ${e.message}")
        }
    }
    
    /**
     * Отправляет данные в AI для анализа
     */
    private suspend fun analyzeWithAi(prompt: String): String? {
        return try {
            // Получаем токен доступа для GigaChat
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
            
            val response = httpClient.post(aiApiUrl) {
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
     * @param apiKey - Authorization Key (Base64), используется в заголовке Authorization: Basic
     */
    private suspend fun getGigaChatToken(apiKey: String): String? {
        return try {
            val tokenUrl = "https://ngw.devices.sberbank.ru:9443/api/v2/oauth"
            
            val requestBody = buildJsonObject {
                put("scope", "GIGACHAT_API_PERS")
            }
            
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
            // Проверяем доступность сервера перед запросом
            try {
                val healthCheck = httpClient.get("${mcpServerUrl.replace("/mcp", "")}/health").status.value
                if (healthCheck !in 200..299) {
                    println("⚠️ MCP сервер может быть недоступен (health check: $healthCheck)")
                }
            } catch (e: Exception) {
                // Health check не критичен, продолжаем
            }
            
            val requestBody = buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", System.currentTimeMillis().toInt())
                put("method", "tools/call")
                putJsonObject("params") {
                    put("name", toolName)
                    put("arguments", arguments)
                }
            }
            
            println("📤 Отправка запроса к MCP серверу: $mcpServerUrl")
            println("   Инструмент: $toolName")
            
            val response = httpClient.post(mcpServerUrl) {
                contentType(ContentType.Application.Json)
                setBody(requestBody.toString())
            }
            
            println("📥 Получен ответ от MCP сервера: ${response.status.value}")
            
            // Проверяем статус ответа
            if (response.status.value !in 200..299) {
                println("❌ MCP сервер вернул ошибку: ${response.status.value}")
                val errorBody = try {
                    response.body<String>()
                } catch (e: Exception) {
                    "Не удалось прочитать тело ответа"
                }
                println("   Тело ответа: $errorBody")
                return null
            }
            
            val responseBody = try {
                response.body<String>()
            } catch (e: Exception) {
                println("❌ Не удалось прочитать ответ от MCP сервера: ${e.message}")
                return null
            }
            
            // Проверяем, что ответ не пустой
            if (responseBody.isBlank()) {
                println("❌ MCP сервер вернул пустой ответ")
                return null
            }
            
            // Парсим JSON с обработкой ошибок
            val json = Json { 
                ignoreUnknownKeys = true
                isLenient = true
            }
            
            val responseJson = try {
                json.parseToJsonElement(responseBody).jsonObject
            } catch (e: Exception) {
                println("❌ Ошибка парсинга JSON ответа от MCP сервера: ${e.message}")
                println("   Ответ сервера: ${responseBody.take(500)}")
                return null
            }
            
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
        } catch (e: java.net.ConnectException) {
            println("❌ Не удалось подключиться к MCP серверу: $mcpServerUrl")
            println("   Убедитесь, что MCP сервер запущен: ./gradlew :server:run")
            null
        } catch (e: java.net.SocketTimeoutException) {
            println("❌ Таймаут при подключении к MCP серверу: $mcpServerUrl")
            println("   Сервер может быть перегружен или недоступен")
            null
        } catch (e: Exception) {
            println("❌ Ошибка при вызове MCP инструмента: ${e.message}")
            println("   Тип ошибки: ${e.javaClass.simpleName}")
            e.printStackTrace()
            null
        }
    }
    
    /**
     * Блокирует выполнение до остановки агента
     */
    suspend fun join() {
        agentJob?.join()
    }
}

/**
 * Запускает AI-агента напоминаний
 */
fun main(args: Array<String>) {
    val checkInterval = args.getOrNull(0)?.toLongOrNull() ?: 60L // минуты
    val summaryIntervalHours = args.getOrNull(1)?.toDoubleOrNull() ?: 6.0 // часы (может быть дробным для тестирования)
    val serverUrl = args.getOrNull(2) ?: "http://localhost:8080/mcp"
    
    println("=".repeat(60))
    println("🤖 AI-АГЕНТ НАПОМИНАНИЙ 24/7")
    println("=".repeat(60))
    println()
    
    if (checkInterval <= 5) {
        println("🧪 ТЕСТОВЫЙ РЕЖИМ АКТИВИРОВАН")
        println("   Проверка каждые: $checkInterval минут(ы)")
        println("   Сводка каждые: $summaryIntervalHours часов")
        println("   Уведомления будут приходить очень часто!")
        println()
    }
    
    val agent = AiReminderAgent(
        mcpServerUrl = serverUrl,
        checkIntervalMinutes = checkInterval,
        summaryIntervalHours = summaryIntervalHours
    )
    
    // Обработка сигнала завершения
    Runtime.getRuntime().addShutdownHook(Thread {
        agent.stop()
    })
    
    // Запускаем агента
    agent.start()
    
    // Блокируем выполнение
    runBlocking {
        try {
            agent.join()
        } catch (e: CancellationException) {
            println("AI-агент остановлен")
        }
    }
}

