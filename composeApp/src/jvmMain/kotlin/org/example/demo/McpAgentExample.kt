package org.example.demo

import io.ktor.client.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.example.demo.chat.*

/**
 * Пример использования агента с MCP инструментом Яндекс Трекера
 * 
 * Демонстрирует:
 * 1. Подключение к локальному MCP серверу
 * 2. Получение списка доступных инструментов
 * 3. Вызов инструмента yandex_tracker
 * 4. Получение результата (количество открытых задач)
 */
fun runMcpAgentExample() {
    println("=".repeat(60))
    println("=== ПРИМЕР: АГЕНТ С MCP ИНСТРУМЕНТОМ ЯНДЕКС ТРЕКЕРА ===")
    println("=".repeat(60))
    println()
    
    val httpClient = createHttpClient()
    val serverUrl = "http://localhost:8080/mcp"
    
    runBlocking {
        try {
            println("1. Подключение к локальному MCP серверу...")
            println("   URL: $serverUrl")
            println()
            
            val mcpClient = McpClient(httpClient, serverUrl)
            val connected = mcpClient.connect()
            
            if (!connected) {
                println("❌ Не удалось подключиться к MCP серверу")
                println("   Убедитесь, что сервер запущен: ./gradlew :server:run")
                return@runBlocking
            }
            
            println("✓ Успешно подключен к MCP серверу")
            println()
            
            // 2. Получаем список доступных инструментов
            println("2. Получение списка доступных инструментов...")
            val tools = mcpClient.listTools()
            println("   Найдено инструментов: ${tools.size}")
            tools.forEach { tool ->
                println("   - ${tool.name}: ${tool.description ?: ""}")
            }
            println()
            
            // 3. Ищем инструмент yandex_tracker
            val trackerTool = tools.find { it.name == "yandex_tracker" }
            if (trackerTool == null) {
                println("❌ Инструмент 'yandex_tracker' не найден")
                return@runBlocking
            }
            
            println("✓ Найден инструмент: yandex_tracker")
            println()
            
            // 4. Агент вызывает инструмент для подсчета открытых задач
            println("3. Агент вызывает инструмент yandex_tracker...")
            println("   Действие: count_open_tasks")
            println()
            
            val result = mcpClient.callTool(
                name = "yandex_tracker",
                arguments = buildJsonObject {
                    put("action", "count_open_tasks")
                    put("queue", "TEST")
                }
            )
            
            if (result != null && !result.isError) {
                val content = result.content.firstOrNull()?.text ?: "Нет результата"
                println("✓ Результат выполнения инструмента:")
                println()
                println("   $content")
                println()
                
                // 5. Агент получает список открытых задач
                println("4. Агент получает список открытых задач...")
                println()
                
                val tasksResult = mcpClient.callTool(
                    name = "yandex_tracker",
                    arguments = buildJsonObject {
                        put("action", "get_open_tasks")
                        put("queue", "TEST")
                    }
                )
                
                if (tasksResult != null && !tasksResult.isError) {
                    val tasksContent = tasksResult.content.firstOrNull()?.text ?: "Нет результата"
                    println("✓ Список открытых задач:")
                    println()
                    println("   $tasksContent")
                    println()
                } else {
                    println("❌ Ошибка при получении списка задач")
                }
                
                // 6. Агент получает информацию о конкретной задаче
                println("5. Агент получает информацию о задаче TEST-1...")
                println()
                
                val taskResult = mcpClient.callTool(
                    name = "yandex_tracker",
                    arguments = buildJsonObject {
                        put("action", "get_task")
                        put("task_id", "TEST-1")
                        put("queue", "TEST")
                    }
                )
                
                if (taskResult != null && !taskResult.isError) {
                    val taskContent = taskResult.content.firstOrNull()?.text ?: "Нет результата"
                    println("✓ Информация о задаче:")
                    println()
                    println("   $taskContent")
                    println()
                } else {
                    println("❌ Ошибка при получении задачи")
                }
                
            } else {
                println("❌ Ошибка при выполнении инструмента")
                result?.content?.forEach { content ->
                    println("   ${content.text}")
                }
            }
            
            // Отключаемся
            println("6. Отключение от MCP сервера...")
            mcpClient.disconnect()
            println("✓ Отключен")
            
        } catch (e: Exception) {
            println("❌ Ошибка: ${e.message}")
            e.printStackTrace()
        } finally {
            httpClient.close()
        }
    }
    
    println()
    println("=".repeat(60))
    println("=== ПРИМЕР ЗАВЕРШЕН ===")
    println("=".repeat(60))
    println()
    println("Нажмите Enter для завершения...")
    try {
        readLine()
    } catch (e: Exception) {
        runBlocking { delay(2000) }
    }
}

/**
 * Пример интеграции MCP инструмента в ChatViewModel
 * Агент может использовать MCP инструменты при ответе на вопросы пользователя
 */
suspend fun exampleAgentWithMcp() {
    val httpClient = createHttpClient()
    val mcpClient = McpClient(httpClient, "http://localhost:8080/mcp")
    
    try {
        // Подключаемся к MCP серверу
        mcpClient.connect()
        
        // Агент получает вопрос от пользователя
        val userQuestion = "Сколько открытых задач в Яндекс Трекере?"
        
        println("Пользователь: $userQuestion")
        println("Агент: Анализирую запрос и вызываю инструмент...")
        
        // Агент определяет, что нужно вызвать инструмент yandex_tracker
        val result = mcpClient.callTool(
            name = "yandex_tracker",
            arguments = buildJsonObject {
                put("action", "count_open_tasks")
            }
        )
        
        // Агент формирует ответ на основе результата
        val agentResponse = if (result != null && !result.isError) {
            val count = result.content.firstOrNull()?.text ?: "неизвестно"
            "Согласно данным Яндекс Трекера: $count"
        } else {
            "Не удалось получить информацию о задачах"
        }
        
        println("Агент: $agentResponse")
        
    } finally {
        mcpClient.disconnect()
        httpClient.close()
    }
}

