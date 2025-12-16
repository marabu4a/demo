package org.example.demo.chat

import io.ktor.client.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/**
 * Минимальный пример подключения к MCP серверу и получения списка инструментов
 */
fun main() {
    // Принудительно выводим в консоль (flush)
    System.out.flush()
    System.err.flush()
    
    println("=".repeat(60))
    println("=== ТЕСТ ПОДКЛЮЧЕНИЯ К MCP СЕРВЕРУ ===")
    println("=".repeat(60))
    println()
    System.out.flush()
    
    // Создаем HTTP клиент
    println("[1/5] Создание HTTP клиента...")
    System.out.flush()
    val httpClient = createHttpClient()
    println("✓ HTTP клиент создан")
    println()
    System.out.flush()
    
    // URL вашего MCP сервера
    // Публичный тестовый сервер: https://mcp-http-demo.arcade.dev/mcp
    // Другие серверы можно найти на: https://www.mcpkit.com/ (более 1000 серверов)
    val serverUrl = "https://mcp-http-demo.arcade.dev/mcp"
    println("[2/5] URL сервера: $serverUrl")
    println()
    System.out.flush()
    
    runBlocking {
        try {
            // Создаем MCP клиент
            println("[3/5] Создание MCP клиента...")
            val mcpClient = McpClient(httpClient, serverUrl)
            println("✓ MCP клиент создан")
            println()
            
            // Подключаемся к серверу
            println("[4/5] Подключение к серверу...")
            println("   Отправка запроса initialize...")
            System.out.flush()
            
            val connected = mcpClient.connect()
            System.out.flush()
            
            if (!connected) {
                println()
                println("❌ НЕ УДАЛОСЬ ПОДКЛЮЧИТЬСЯ К MCP СЕРВЕРУ")
                println()
                println("Возможные причины:")
                println("  - Неправильный URL сервера")
                println("  - Сервер недоступен")
                println("  - Сервер не поддерживает MCP протокол")
                println("  - Проблемы с сетью")
                println()
                println("Проверьте логи выше для деталей ошибки.")
                return@runBlocking
            }
            
            println("✓ Успешно подключен к MCP серверу")
            println()
            
            // Получаем список доступных инструментов
            println("[5/5] Получение списка доступных инструментов...")
            val tools = mcpClient.listTools()
            
            println()
            println("=".repeat(60))
            println("=== РЕЗУЛЬТАТ ===")
            println("=".repeat(60))
            println("Найдено инструментов: ${tools.size}")
            println()
            
            if (tools.isEmpty()) {
                println("   (Список инструментов пуст)")
            } else {
                tools.forEachIndexed { index, tool ->
                    println("${index + 1}. ${tool.name}")
                    tool.description?.let { 
                        println("   Описание: $it")
                    }
                    println()
                }
            }
            
            // Отключаемся
            println("Отключение от сервера...")
            mcpClient.disconnect()
            println("✓ Отключен от MCP сервера")
            
        } catch (e: Exception) {
            println()
            println("=".repeat(60))
            println("❌ ОШИБКА")
            println("=".repeat(60))
            println("Сообщение: ${e.message}")
            println()
            println("Тип ошибки: ${e.javaClass.simpleName}")
            println()
            println("Стек вызовов:")
            e.printStackTrace()
        } finally {
            println()
            println("Закрытие HTTP клиента...")
            httpClient.close()
            println("✓ HTTP клиент закрыт")
        }
    }
    
    println()
    println("=".repeat(60))
    println("=== ТЕСТ ЗАВЕРШЕН ===")
    println("=".repeat(60))
    
    // Принудительно выводим все в консоль
    System.out.flush()
    System.err.flush()
    
    // Добавляем небольшую задержку, чтобы вывод был виден
    // (в некоторых IDE консоль закрывается сразу)
    runBlocking {
        println()
        println("Ожидание 2 секунды перед завершением...")
        System.out.flush()
        delay(2000)
    }
    
    println("Программа завершена.")
    System.out.flush()
}

/**
 * Пример использования через McpClientManager (для управления несколькими серверами)
 */
suspend fun exampleWithManager(httpClient: HttpClient) {
    val manager = McpClientManager(httpClient)
    
    try {
        // Подключаем сервер
        val connected = manager.connectServer("my-server", "https://your-mcp-server.com/mcp")
        if (!connected) {
            println("Не удалось подключиться")
            return
        }
        
        // Получаем клиент
        val client = manager.getClient("my-server")
        if (client != null) {
            // Получаем список инструментов
            val tools = client.listTools()
            println("Найдено инструментов: ${tools.size}")
            tools.forEach { tool ->
                println("- ${tool.name}: ${tool.description ?: "без описания"}")
            }
        }
        
        // Отключаемся
        manager.disconnectServer("my-server")
        
    } catch (e: Exception) {
        println("Ошибка: ${e.message}")
    }
}
