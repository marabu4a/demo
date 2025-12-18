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
 * Агент для периодической проверки напоминаний и выдачи сводки
 * Работает 24/7 и периодически выдает сводку по напоминаниям
 */
class ReminderAgent(
    private val mcpServerUrl: String = "http://localhost:8080/mcp",
    private val checkIntervalMinutes: Long = 60, // Проверка каждый час
    private val summaryIntervalHours: Long = 6 // Полная сводка каждые 6 часов
) {
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
            println("⚠️ Агент уже запущен")
            return
        }
        
        isRunning = true
        println("🚀 Запуск агента напоминаний...")
        println("   URL сервера: $mcpServerUrl")
        println("   Интервал проверки: $checkIntervalMinutes минут")
        println("   Интервал сводки: $summaryIntervalHours часов")
        println()
        
        agentJob = CoroutineScope(Dispatchers.Default).launch {
            var lastSummaryTime = System.currentTimeMillis()
            
            while (isRunning) {
                try {
                    val now = System.currentTimeMillis()
                    val timeSinceLastSummary = (now - lastSummaryTime) / (1000 * 60 * 60) // в часах
                    
                    // Проверяем просроченные напоминания
                    checkDueReminders()
                    
                    // Если прошло достаточно времени, выдаем полную сводку
                    if (timeSinceLastSummary >= summaryIntervalHours) {
                        getFullSummary()
                        lastSummaryTime = now
                    }
                    
                    // Ждем до следующей проверки
                    delay(checkIntervalMinutes * 60 * 1000)
                } catch (e: Exception) {
                    println("❌ Ошибка в агенте: ${e.message}")
                    e.printStackTrace()
                    // Продолжаем работу даже при ошибке
                    delay(checkIntervalMinutes * 60 * 1000)
                }
            }
        }
        
        println("✅ Агент запущен и работает в фоновом режиме")
        println("   Нажмите Ctrl+C для остановки")
    }
    
    /**
     * Останавливает агента
     */
    fun stop() {
        if (!isRunning) {
            println("⚠️ Агент не запущен")
            return
        }
        
        println("🛑 Остановка агента...")
        isRunning = false
        agentJob?.cancel()
        httpClient.close()
        println("✅ Агент остановлен")
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
                    println("🔔 ПРОСРОЧЕННЫЕ НАПОМИНАНИЯ:")
                    println(content)
                    println()
                }
            }
        } catch (e: Exception) {
            println("⚠️ Ошибка при проверке просроченных напоминаний: ${e.message}")
        }
    }
    
    /**
     * Получает полную сводку по напоминаниям
     */
    private suspend fun getFullSummary() {
        try {
            val result = callMcpTool("reminder", buildJsonObject {
                put("action", "get_summary")
            })
            
            if (result != null && !result.isError) {
                val summary = result.content.firstOrNull()?.text ?: ""
                if (summary.isNotEmpty()) {
                    println("=".repeat(60))
                    println("📋 СВОДКА ПО НАПОМИНАНИЯМ")
                    println("   Время: ${java.time.Instant.now()}")
                    println("=".repeat(60))
                    println()
                    println(summary)
                    println("=".repeat(60))
                    println()
                }
            }
        } catch (e: Exception) {
            println("⚠️ Ошибка при получении сводки: ${e.message}")
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
    
    /**
     * Блокирует выполнение до остановки агента
     */
    suspend fun join() {
        agentJob?.join()
    }
}

/**
 * Результат вызова MCP инструмента
 */
data class McpToolResult(
    val content: List<McpContentItem>,
    val isError: Boolean
)

data class McpContentItem(
    val text: String
)

/**
 * Запускает агента напоминаний
 */
fun main(args: Array<String>) {
    val checkInterval = args.getOrNull(0)?.toLongOrNull() ?: 60L // минуты
    val summaryInterval = args.getOrNull(1)?.toLongOrNull() ?: 6L // часы
    val serverUrl = args.getOrNull(2) ?: "http://localhost:8080/mcp"
    
    println("=".repeat(60))
    println("🤖 АГЕНТ НАПОМИНАНИЙ 24/7")
    println("=".repeat(60))
    println()
    
    val agent = ReminderAgent(
        mcpServerUrl = serverUrl,
        checkIntervalMinutes = checkInterval,
        summaryIntervalHours = summaryInterval
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
            println("Агент остановлен")
        }
    }
}

