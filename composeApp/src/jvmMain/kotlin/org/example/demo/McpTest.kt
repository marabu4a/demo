package org.example.demo

import io.ktor.client.*
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.example.demo.chat.*

/**
 * Тестовый файл для запуска MCP клиента
 * 
 * СПОСОБЫ ЗАПУСКА:
 * 1. В IntelliJ IDEA: ПКМ на функции main() -> Run 'McpTestKt.main()'
 * 2. Через терминал: ./gradlew :composeApp:run --args="mcp-test"
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
    
    // URL MCP сервера (можно изменить)
    // 
    // Известные публичные MCP серверы для тестирования:
    // 1. https://mcp-http-demo.arcade.dev/mcp - демо сервер от Arcade
    // 2. https://www.mcpkit.com/ - каталог с более чем 1000 серверов
    // 3. MCP Playground: https://mcpsplayground.com/ - интерактивная платформа для тестирования
    // 4. MCP Playground Online: https://mcpplaygroundonline.com/ - онлайн тестирование без установки
    // 5. HealthyMCP: https://healthymcp.com/checkup - проверка здоровья серверов
    //
    // Для поиска других серверов:
    // - https://www.mcpkit.com/ - каталог публичных MCP серверов
    // - GitHub: поиск по "mcp server" или "model context protocol"
    val serverUrl = "https://knowledge-mcp.global.api.aws"
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
    
    // Добавляем ожидание ввода, чтобы консоль не закрывалась
    println()
    println("Нажмите Enter для завершения...")
    System.out.flush()
    try {
        readLine()
    } catch (e: Exception) {
        // Если readLine() не работает (например, в некоторых IDE), просто ждем
        runBlocking {
            delay(3000)
        }
    }
    
    println("Программа завершена.")
    System.out.flush()
}

/**
 * Диагностическая функция для проверки подключения к MCP серверу
 * Показывает детальную информацию о попытках подключения
 */
fun testMcpConnectionDiagnostics() {
    val httpClient = createHttpClient()
    
    // Список серверов для тестирования
    val testServers = listOf(
        "https://mcp-http-demo.arcade.dev/mcp",
        "https://mcp.shawndurrani.ai/sse",
        "https://notion.mcpservers.org",
        "https://sentry.mcpservers.org"
    )
    
    println("=".repeat(60))
    println("=== ДИАГНОСТИКА ПОДКЛЮЧЕНИЯ К MCP СЕРВЕРАМ ===")
    println("=".repeat(60))
    println()
    
    runBlocking {
        testServers.forEach { serverUrl ->
            println("\n${"=".repeat(60)}")
            println("Тестирование: $serverUrl")
            println("=".repeat(60))
            
            try {
                // 1. Проверка базового URL
                println("\n1. Проверка доступности базового URL...")
                val baseUrl = serverUrl.replace(Regex("/mcp$|/sse$|/message$"), "")
                try {
                    val testResponse = httpClient.get(baseUrl) {
                        timeout {
                            requestTimeoutMillis = 5000
                        }
                    }
                    println("   ✓ Базовый URL доступен: ${testResponse.status.value}")
                } catch (e: Exception) {
                    println("   ⚠ Базовый URL недоступен: ${e.message}")
                }
                
                // 2. Попытка подключения
                println("\n2. Попытка подключения к MCP серверу...")
                val mcpClient = McpClient(httpClient, serverUrl)
                val connected = mcpClient.connect()
                
                if (connected) {
                    println("   ✓ Подключение успешно!")
                    
                    // 3. Получение инструментов
                    println("\n3. Получение списка инструментов...")
                    try {
                        val tools = mcpClient.listTools()
                        println("   ✓ Найдено инструментов: ${tools.size}")
                        if (tools.isNotEmpty()) {
                            tools.take(3).forEach { tool ->
                                println("      - ${tool.name}: ${tool.description?.take(50) ?: ""}")
                            }
                        }
                    } catch (e: Exception) {
                        println("   ⚠ Ошибка при получении инструментов: ${e.message}")
                    }
                    
                    // 4. Получение ресурсов
                    println("\n4. Получение списка ресурсов...")
                    try {
                        val resources = mcpClient.listResources()
                        println("   ✓ Найдено ресурсов: ${resources.size}")
                    } catch (e: Exception) {
                        println("   ⚠ Ошибка при получении ресурсов: ${e.message}")
                    }
                    
                    mcpClient.disconnect()
                } else {
                    println("   ❌ Подключение не удалось")
                    println("\n   Возможные причины:")
                    println("     - Сервер не поддерживает MCP протокол")
                    println("     - Неправильный endpoint")
                    println("     - Требуется аутентификация")
                    println("     - Проблемы с сетью")
                    println("\n   Проверьте логи выше для деталей.")
                }
            } catch (e: Exception) {
                println("   ❌ Критическая ошибка: ${e.message}")
                e.printStackTrace()
            }
        }
    }
    
    println("\n${"=".repeat(60)}")
    println("=== ДИАГНОСТИКА ЗАВЕРШЕНА ===")
    println("=".repeat(60))
    println("\nНажмите Enter для завершения...")
    try {
        readLine()
    } catch (e: Exception) {
        runBlocking { delay(2000) }
    }
    
    httpClient.close()
}

/**
 * Альтернативный пример через McpClientManager
 */
fun testWithManager() {
    val httpClient = createHttpClient()
    val serverUrl = "https://your-mcp-server.com/mcp"
    
    runBlocking {
        val manager = McpClientManager(httpClient)
        
        try {
            println("Подключение через менеджер...")
            val connected = manager.connectServer("test-server", serverUrl)
            
            if (connected) {
                val client = manager.getClient("test-server")
                val tools = client?.listTools() ?: emptyList()
                println("Инструменты: ${tools.map { it.name }}")
                
                manager.disconnectServer("test-server")
            }
        } catch (e: Exception) {
            println("Ошибка: ${e.message}")
            e.printStackTrace()
        } finally {
            httpClient.close()
        }
    }
}

/**
 * Альтернативная функция для тестирования (без ожидания ввода)
 */
fun testMcp() = main()

/**
 * Быстрый тест одного сервера с детальным выводом
 */
fun quickTest(serverUrl: String = "https://knowledge-mcp.global.api.aws") {
    val httpClient = createHttpClient()
    
    println("Тестирование: $serverUrl")
    println()
    
    runBlocking {
        try {
            val mcpClient = McpClient(httpClient, serverUrl)
            val connected = mcpClient.connect()
            
            if (connected) {
                println("✓ Подключено!")
                val tools = mcpClient.listTools()
                println("Инструментов: ${tools.size}")
            } else {
                println("❌ Не удалось подключиться")
            }
        } catch (e: Exception) {
            println("❌ Ошибка: ${e.message}")
            e.printStackTrace()
        } finally {
            httpClient.close()
        }
    }
}

/**
 * Пример использования SSE соединения для получения сообщений от MCP сервера
 */
fun testSseConnection() {
    val httpClient = createHttpClient()
    val serverUrl = "https://mcp-http-demo.arcade.dev/mcp"
    
    runBlocking {
        val mcpClient = McpClient(httpClient, serverUrl)
        
        try {
            println("Подключение к MCP серверу...")
            val connected = mcpClient.connect()
            
            if (!connected) {
                println("Не удалось подключиться")
                return@runBlocking
            }
            
            println("✓ Подключено")
            println("SSE соединение: ${if (mcpClient.isSseConnected()) "активно" else "неактивно"}")
            println()
            println("Ожидание сообщений от сервера через SSE...")
            println("(Нажмите Ctrl+C для остановки)")
            println()
            
            // Подписываемся на поток сообщений
            mcpClient.getMessageFlow().collect { message ->
                println("📨 Получено SSE сообщение:")
                println("   Метод: ${message.method ?: "notification"}")
                message.params?.let { params ->
                    println("   Параметры: $params")
                }
                println()
            }
        } catch (e: Exception) {
            println("Ошибка: ${e.message}")
            e.printStackTrace()
        } finally {
            mcpClient.disconnect()
            httpClient.close()
        }
    }
}




