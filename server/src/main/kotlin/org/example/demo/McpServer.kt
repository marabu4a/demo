package org.example.demo

import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.request.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

// Импортируем reminderService из ReminderService.kt
// reminderService определен как глобальный экземпляр в ReminderService.kt

/**
 * Простой локальный MCP сервер для тестирования
 * Запускается на http://localhost:8080/mcp
 */
fun main() {
    embeddedServer(Netty, port = SERVER_PORT, host = "0.0.0.0", module = Application::mcpModule)
        .start(wait = true)
}

fun Application.mcpModule() {
    install(ContentNegotiation) {
        json(
            Json {
                ignoreUnknownKeys = true
                prettyPrint = true
            }
        )
    }
    
    install(CORS) {
        allowMethod(HttpMethod.Options)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Get)
        allowHeader(HttpHeaders.ContentType)
        allowHeader("X-Session-Id")
        anyHost()
    }
    
    routing {
        
        // GET для /mcp - информация об endpoint (должен быть ПЕРЕД POST)
        get("/mcp") {
            call.respondText(
                """
                MCP Server Endpoint
                
                This endpoint accepts POST requests with JSON-RPC 2.0 format.
                
                Example request:
                POST /mcp
                Content-Type: application/json
                
                {
                  "jsonrpc": "2.0",
                  "id": 1,
                  "method": "initialize",
                  "params": {
                    "protocolVersion": "2024-11-05",
                    "capabilities": {},
                    "clientInfo": {
                      "name": "test-client",
                      "version": "1.0.0"
                    }
                  }
                }
                
                Available methods:
                - initialize
                - tools/list
                - tools/call
                - resources/list
                - resources/read
                
                Use POST method to interact with the server.
                """.trimIndent(),
                ContentType.Text.Plain
            )
        }
        
        // POST для /mcp - основной endpoint для MCP запросов
        post("/mcp") {
            call.handleMcpRequest()
        }
        
        // Альтернативные endpoints
        post("/mcp/message") {
            call.handleMcpRequest()
        }
        
        get("/mcp/message") {
            call.respondText("MCP message endpoint. Use POST method.", ContentType.Text.Plain)
        }
        
        post("/message") {
            call.handleMcpRequest()
        }
        
        get("/message") {
            call.respondText("MCP message endpoint. Use POST method.", ContentType.Text.Plain)
        }
        
        // Health check
        get("/health") {
            call.respondText("OK", ContentType.Text.Plain)
        }
        
        // Информация о сервере
        get("/") {
            call.respondText(
                """
                MCP Server is running!
                
                Endpoints:
                - POST /mcp - MCP protocol endpoint (JSON-RPC 2.0)
                - GET /mcp - Information about the endpoint
                - POST /mcp/message - Alternative endpoint
                - POST /message - Alternative endpoint
                - GET /health - Health check
                
                Server URL: http://localhost:8080/mcp
                
                To test the server, use POST requests with JSON-RPC 2.0 format.
                For example, use curl:
                
                curl -X POST http://localhost:8080/mcp \\
                  -H "Content-Type: application/json" \\
                  -d '{
                    "jsonrpc": "2.0",
                    "id": 1,
                    "method": "tools/list"
                  }'
                """.trimIndent(),
                ContentType.Text.Plain
            )
        }
    }
}

/**
 * Обрабатывает MCP запросы
 */
suspend fun ApplicationCall.handleMcpRequest() {
    try {
        val requestBody = receive<String>()
        println("Received MCP request: $requestBody")
        
        val json = Json { ignoreUnknownKeys = true }
        val mcpRequest = json.decodeFromString<McpRequest>(requestBody)
        
        // Генерируем session ID если его нет
        val sessionId = request.headers["X-Session-Id"] 
            ?: "session_${System.currentTimeMillis()}"
        
        val responseJson = when (mcpRequest.method) {
            "initialize" -> handleInitialize(mcpRequest)
            "tools/list" -> handleToolsList(mcpRequest)
            "tools/call" -> handleToolCall(mcpRequest, requestBody)
            "resources/list" -> handleResourcesList(mcpRequest)
            "resources/read" -> handleResourceRead(mcpRequest, requestBody)
            else -> createErrorResponse(mcpRequest.id, -32601, "Method not found: ${mcpRequest.method}")
        }
        
        // Устанавливаем session ID в заголовках ответа
        response.headers.append("X-Session-Id", sessionId)
        respondText(
            json.encodeToString(responseJson),
            ContentType.Application.Json,
            HttpStatusCode.OK
        )
    } catch (e: Exception) {
        println("Error handling MCP request: ${e.message}")
        e.printStackTrace()
        respondText(
            """{"jsonrpc":"2.0","error":{"code":-32700,"message":"Parse error: ${e.message}"},"id":null}""",
            ContentType.Application.Json,
            HttpStatusCode.BadRequest
        )
    }
}

/**
 * Обрабатывает initialize запрос
 */
fun handleInitialize(request: McpRequest): JsonObject {
    return buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", request.id)
        putJsonObject("result") {
            put("protocolVersion", "2024-11-05")
            putJsonObject("capabilities") {
                // Указываем, что сервер поддерживает
            }
            putJsonObject("serverInfo") {
                put("name", "local-mcp-server")
                put("version", "1.0.0")
            }
        }
    }
}

/**
 * Обрабатывает tools/list запрос
 */
fun handleToolsList(request: McpRequest): JsonObject {
    return buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", request.id)
        putJsonObject("result") {
            putJsonArray("tools") {
                // Инструмент 1: Echo
                addJsonObject {
                    put("name", "echo")
                    put("description", "Echoes back the input text")
                    putJsonObject("inputSchema") {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("text") {
                                put("type", "string")
                                put("description", "Text to echo back")
                            }
                        }
                        putJsonArray("required") {
                            add("text")
                        }
                    }
                }
                
                // Инструмент 2: Get Current Time
                addJsonObject {
                    put("name", "get_current_time")
                    put("description", "Returns the current server time")
                    putJsonObject("inputSchema") {
                        put("type", "object")
                        putJsonObject("properties") { }
                    }
                }
                
                // Инструмент 3: Calculate
                addJsonObject {
                    put("name", "calculate")
                    put("description", "Performs basic arithmetic operations")
                    putJsonObject("inputSchema") {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("expression") {
                                put("type", "string")
                                put("description", "Mathematical expression to evaluate (e.g., '2 + 2')")
                            }
                        }
                        putJsonArray("required") {
                            add("expression")
                        }
                    }
                }
                
                // Инструмент 4: Yandex Tracker
                addJsonObject {
                    put("name", "yandex_tracker")
                    put("description", "Работа с задачами Яндекс Трекера. Получение списка задач, подсчет открытых задач и т.д.")
                    putJsonObject("inputSchema") {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("action") {
                                put("type", "string")
                                put("description", "Действие: 'get_open_tasks' - получить открытые задачи, 'count_open_tasks' - подсчитать открытые задачи, 'get_task' - получить задачу по ID, 'create_task' - создать новую задачу")
                                putJsonArray("enum") {
                                    add("get_open_tasks")
                                    add("count_open_tasks")
                                    add("get_task")
                                    add("create_task")
                                }
                            }
                            putJsonObject("task_id") {
                                put("type", "string")
                                put("description", "ID задачи (требуется для action='get_task')")
                            }
                            putJsonObject("summary") {
                                put("type", "string")
                                put("description", "Название задачи (требуется для action='create_task')")
                            }
                            putJsonObject("description") {
                                put("type", "string")
                                put("description", "Описание задачи (опционально, для action='create_task')")
                            }
                            putJsonObject("queue") {
                                put("type", "string")
                                put("description", "Очередь задач (опционально, по умолчанию 'TEST')")
                            }
                        }
                        putJsonArray("required") {
                            add("action")
                        }
                    }
                }
                
                // Инструмент 5: Reminder
                addJsonObject {
                    put("name", "reminder")
                    put("description", "Управление напоминаниями. Создание, просмотр, удаление напоминаний и получение сводки.")
                    putJsonObject("inputSchema") {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("action") {
                                put("type", "string")
                                put("description", "Действие: 'create' - создать напоминание, 'list' - список напоминаний, 'get' - получить напоминание по ID, 'delete' - удалить напоминание, 'complete' - отметить как выполненное, 'get_summary' - получить сводку, 'get_due' - получить просроченные напоминания")
                                putJsonArray("enum") {
                                    add("create")
                                    add("list")
                                    add("get")
                                    add("delete")
                                    add("complete")
                                    add("get_summary")
                                    add("get_due")
                                }
                            }
                            putJsonObject("id") {
                                put("type", "string")
                                put("description", "ID напоминания (требуется для action='get', 'delete', 'complete')")
                            }
                            putJsonObject("title") {
                                put("type", "string")
                                put("description", "Название напоминания (требуется для action='create')")
                            }
                            putJsonObject("description") {
                                put("type", "string")
                                put("description", "Описание напоминания (опционально, для action='create')")
                            }
                            putJsonObject("dueDate") {
                                put("type", "number")
                                put("description", "Время напоминания в миллисекундах (timestamp, опционально, для action='create')")
                            }
                            putJsonObject("priority") {
                                put("type", "string")
                                put("description", "Приоритет: 'low', 'normal', 'high' (опционально, по умолчанию 'normal', для action='create')")
                                putJsonArray("enum") {
                                    add("low")
                                    add("normal")
                                    add("high")
                                }
                            }
                            putJsonObject("category") {
                                put("type", "string")
                                put("description", "Категория напоминания (опционально, для action='create')")
                            }
                            putJsonObject("includeCompleted") {
                                put("type", "boolean")
                                put("description", "Включать выполненные напоминания в список (опционально, по умолчанию true, для action='list')")
                            }
                        }
                        putJsonArray("required") {
                            add("action")
                        }
                    }
                }
                
                // Инструмент 6: Save Information
                addJsonObject {
                    put("name", "save_info")
                    put("description", "Сохраняет информацию в JSON файл на устройстве пользователя. Поддерживает сохранение содержимого веб-страниц, summary и метаданных.")
                    putJsonObject("inputSchema") {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("action") {
                                put("type", "string")
                                put("description", "Действие: 'save' - сохранить информацию, 'get' - получить по ID, 'search' - поиск по тексту, 'search_by_tags' - поиск по тегам, 'list' - список всей информации, 'stats' - статистика, 'delete' - удалить")
                                putJsonArray("enum") {
                                    add("save")
                                    add("get")
                                    add("search")
                                    add("search_by_tags")
                                    add("list")
                                    add("stats")
                                    add("delete")
                                }
                            }
                            putJsonObject("title") {
                                put("type", "string")
                                put("description", "Заголовок информации (требуется для action='save')")
                            }
                            putJsonObject("content") {
                                put("type", "string")
                                put("description", "Содержимое информации (требуется для action='save')")
                            }
                            putJsonObject("source") {
                                put("type", "string")
                                put("description", "Источник информации, например URL (опционально, для action='save')")
                            }
                            putJsonObject("summary") {
                                put("type", "string")
                                put("description", "Краткая сводка информации (опционально, для action='save')")
                            }
                            putJsonObject("tags") {
                                put("type", "array")
                                put("description", "Теги для категоризации (опционально, для action='save')")
                                putJsonObject("items") {
                                    put("type", "string")
                                }
                            }
                            putJsonObject("metadata") {
                                put("type", "object")
                                put("description", "Дополнительные метаданные (опционально, для action='save')")
                            }
                            putJsonObject("id") {
                                put("type", "string")
                                put("description", "ID информации (требуется для action='get' или 'delete')")
                            }
                            putJsonObject("query") {
                                put("type", "string")
                                put("description", "Поисковый запрос (требуется для action='search')")
                            }
                        }
                        putJsonArray("required") {
                            add("action")
                        }
                    }
                }
            }
        }
    }
}

/**
 * Обрабатывает tools/call запрос
 */
suspend fun handleToolCall(request: McpRequest, requestBody: String): JsonObject {
    val json = Json { ignoreUnknownKeys = true }
    val requestJson = json.parseToJsonElement(requestBody).jsonObject
    val params = requestJson["params"]?.jsonObject
    val toolName = params?.get("name")?.jsonPrimitive?.content ?: ""
    val arguments = params?.get("arguments")?.jsonObject
    
    val result = when (toolName) {
        "echo" -> {
            val text = arguments?.get("text")?.jsonPrimitive?.content ?: ""
            buildJsonObject {
                putJsonArray("content") {
                    addJsonObject {
                        put("type", "text")
                        put("text", "Echo: $text")
                    }
                }
                put("isError", false)
            }
        }
        "get_current_time" -> {
            val currentTime = java.time.Instant.now().toString()
            buildJsonObject {
                putJsonArray("content") {
                    addJsonObject {
                        put("type", "text")
                        put("text", "Current server time: $currentTime")
                    }
                }
                put("isError", false)
            }
        }
        "calculate" -> {
            try {
                val expression = arguments?.get("expression")?.jsonPrimitive?.content ?: ""
                // Простой калькулятор (для безопасности используйте более безопасный парсер)
                val result = evaluateExpression(expression)
                buildJsonObject {
                    putJsonArray("content") {
                        addJsonObject {
                            put("type", "text")
                            put("text", "Result: $result")
                        }
                    }
                    put("isError", false)
                }
            } catch (e: Exception) {
                buildJsonObject {
                    putJsonArray("content") {
                        addJsonObject {
                            put("type", "text")
                            put("text", "Error: ${e.message}")
                        }
                    }
                    put("isError", true)
                }
            }
        }
        "yandex_tracker" -> {
            try {
                val action = arguments?.get("action")?.jsonPrimitive?.content ?: ""
                val taskId = arguments?.get("task_id")?.jsonPrimitive?.content
                val queue = arguments?.get("queue")?.jsonPrimitive?.content ?: "TEST"
                
                val result = when (action) {
                    "get_open_tasks" -> {
                        val tasks = getOpenTasks(queue)
                        buildJsonObject {
                            putJsonArray("content") {
                                addJsonObject {
                                    put("type", "text")
                                    put("text", "Открытые задачи в очереди '$queue':\n\n${tasks.joinToString("\n") { "- ${it.key}: ${it.summary}" }}\n\nВсего: ${tasks.size} задач")
                                }
                            }
                            put("isError", false)
                        }
                    }
                    "count_open_tasks" -> {
                        val count = countOpenTasks(queue)
                        buildJsonObject {
                            putJsonArray("content") {
                                addJsonObject {
                                    put("type", "text")
                                    put("text", "Количество открытых задач в очереди '$queue': $count")
                                }
                            }
                            put("isError", false)
                        }
                    }
                    "get_task" -> {
                        if (taskId == null) {
                            buildJsonObject {
                                putJsonArray("content") {
                                    addJsonObject {
                                        put("type", "text")
                                        put("text", "Ошибка: требуется параметр 'task_id' для получения задачи")
                                    }
                                }
                                put("isError", true)
                            }
                        } else {
                            val task = getTaskById(taskId, queue)
                            if (task != null) {
                                buildJsonObject {
                                    putJsonArray("content") {
                                        addJsonObject {
                                            put("type", "text")
                                            put("text", "Задача ${task.key}:\nСтатус: ${task.status}\nНазвание: ${task.summary}\nОписание: ${task.description ?: "Нет описания"}")
                                        }
                                    }
                                    put("isError", false)
                                }
                            } else {
                                buildJsonObject {
                                    putJsonArray("content") {
                                        addJsonObject {
                                            put("type", "text")
                                            put("text", "Задача с ID '$taskId' не найдена")
                                        }
                                    }
                                    put("isError", true)
                                }
                            }
                        }
                    }
                    "create_task" -> {
                        val summary = arguments?.get("summary")?.jsonPrimitive?.content
                        val description = arguments?.get("description")?.jsonPrimitive?.content
                        
                        if (summary.isNullOrBlank()) {
                            buildJsonObject {
                                putJsonArray("content") {
                                    addJsonObject {
                                        put("type", "text")
                                        put("text", "Ошибка: требуется параметр 'summary' для создания задачи")
                                    }
                                }
                                put("isError", true)
                            }
                        } else {
                            val newTask = createTask(summary, description, queue)
                            buildJsonObject {
                                putJsonArray("content") {
                                    addJsonObject {
                                        put("type", "text")
                                        put("text", "Задача успешно создана!\n\nКлюч: ${newTask.key}\nНазвание: ${newTask.summary}\nСтатус: ${newTask.status}\n${if (newTask.description != null) "Описание: ${newTask.description}\n" else ""}")
                                    }
                                }
                                put("isError", false)
                            }
                        }
                    }
                    else -> {
                        buildJsonObject {
                            putJsonArray("content") {
                                addJsonObject {
                                    put("type", "text")
                                    put("text", "Неизвестное действие: $action. Доступные действия: get_open_tasks, count_open_tasks, get_task, create_task")
                                }
                            }
                            put("isError", true)
                        }
                    }
                }
                result
            } catch (e: Exception) {
                buildJsonObject {
                    putJsonArray("content") {
                        addJsonObject {
                            put("type", "text")
                            put("text", "Ошибка при работе с Яндекс Трекером: ${e.message}")
                        }
                    }
                    put("isError", true)
                }
            }
        }
        "reminder" -> {
            try {
                val action = arguments?.get("action")?.jsonPrimitive?.content ?: ""
                
                val result = when (action) {
                    "create" -> {
                        val title = arguments?.get("title")?.jsonPrimitive?.content
                        val description = arguments?.get("description")?.jsonPrimitive?.content
                        val dueDate = arguments?.get("dueDate")?.jsonPrimitive?.longOrNull
                        val priority = arguments?.get("priority")?.jsonPrimitive?.content ?: "normal"
                        val category = arguments?.get("category")?.jsonPrimitive?.content
                        
                        if (title.isNullOrBlank()) {
                            buildJsonObject {
                                putJsonArray("content") {
                                    addJsonObject {
                                        put("type", "text")
                                        put("text", "Ошибка: требуется параметр 'title' для создания напоминания")
                                    }
                                }
                                put("isError", true)
                            }
                        } else {
                            val reminder = reminderService.createReminder(
                                title = title,
                                description = description,
                                dueDate = dueDate,
                                priority = priority,
                                category = category
                            )
                            buildJsonObject {
                                putJsonArray("content") {
                                    addJsonObject {
                                        put("type", "text")
                                        put("text", "Напоминание создано!\n\nID: ${reminder.id}\nНазвание: ${reminder.title}\n${if (reminder.description != null) "Описание: ${reminder.description}\n" else ""}Приоритет: ${reminder.priority}\n${if (reminder.dueDate != null) "Напомнить: ${java.time.Instant.ofEpochMilli(reminder.dueDate).toString()}\n" else ""}${if (reminder.category != null) "Категория: ${reminder.category}\n" else ""}")
                                    }
                                }
                                put("isError", false)
                            }
                        }
                    }
                    "list" -> {
                        val includeCompleted = arguments?.get("includeCompleted")?.jsonPrimitive?.booleanOrNull ?: true
                        val reminders = reminderService.getAllReminders(includeCompleted)
                        
                        if (reminders.isEmpty()) {
                            buildJsonObject {
                                putJsonArray("content") {
                                    addJsonObject {
                                        put("type", "text")
                                        put("text", "Нет напоминаний")
                                    }
                                }
                                put("isError", false)
                            }
                        } else {
                            val formatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                                .withZone(java.time.ZoneId.systemDefault())
                            val listText = reminders.joinToString("\n\n") { reminder ->
                                buildString {
                                    append("ID: ${reminder.id}\n")
                                    append("Название: ${reminder.title}\n")
                                    if (reminder.description != null) {
                                        append("Описание: ${reminder.description}\n")
                                    }
                                    append("Приоритет: ${reminder.priority}\n")
                                    if (reminder.dueDate != null) {
                                        append("Напомнить: ${formatter.format(java.time.Instant.ofEpochMilli(reminder.dueDate))}\n")
                                    }
                                    if (reminder.category != null) {
                                        append("Категория: ${reminder.category}\n")
                                    }
                                    append("Статус: ${if (reminder.completed) "✅ Выполнено" else "⏳ Ожидает"}")
                                }
                            }
                            buildJsonObject {
                                putJsonArray("content") {
                                    addJsonObject {
                                        put("type", "text")
                                        put("text", "Список напоминаний (${reminders.size}):\n\n$listText")
                                    }
                                }
                                put("isError", false)
                            }
                        }
                    }
                    "get" -> {
                        val id = arguments?.get("id")?.jsonPrimitive?.content
                        if (id.isNullOrBlank()) {
                            buildJsonObject {
                                putJsonArray("content") {
                                    addJsonObject {
                                        put("type", "text")
                                        put("text", "Ошибка: требуется параметр 'id' для получения напоминания")
                                    }
                                }
                                put("isError", true)
                            }
                        } else {
                            val reminder = reminderService.getReminderById(id)
                            if (reminder != null) {
                                val formatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                                    .withZone(java.time.ZoneId.systemDefault())
                                buildJsonObject {
                                    putJsonArray("content") {
                                        addJsonObject {
                                            put("type", "text")
                                            put("text", "Напоминание:\n\nID: ${reminder.id}\nНазвание: ${reminder.title}\n${if (reminder.description != null) "Описание: ${reminder.description}\n" else ""}Приоритет: ${reminder.priority}\n${if (reminder.dueDate != null) "Напомнить: ${formatter.format(java.time.Instant.ofEpochMilli(reminder.dueDate))}\n" else ""}${if (reminder.category != null) "Категория: ${reminder.category}\n" else ""}Статус: ${if (reminder.completed) "✅ Выполнено" else "⏳ Ожидает"}\nСоздано: ${formatter.format(java.time.Instant.ofEpochMilli(reminder.createdAt))}")
                                        }
                                    }
                                    put("isError", false)
                                }
                            } else {
                                buildJsonObject {
                                    putJsonArray("content") {
                                        addJsonObject {
                                            put("type", "text")
                                            put("text", "Напоминание с ID '$id' не найдено")
                                        }
                                    }
                                    put("isError", true)
                                }
                            }
                        }
                    }
                    "delete" -> {
                        val id = arguments?.get("id")?.jsonPrimitive?.content
                        if (id.isNullOrBlank()) {
                            buildJsonObject {
                                putJsonArray("content") {
                                    addJsonObject {
                                        put("type", "text")
                                        put("text", "Ошибка: требуется параметр 'id' для удаления напоминания")
                                    }
                                }
                                put("isError", true)
                            }
                        } else {
                            val deleted = reminderService.deleteReminder(id)
                            if (deleted) {
                                buildJsonObject {
                                    putJsonArray("content") {
                                        addJsonObject {
                                            put("type", "text")
                                            put("text", "Напоминание с ID '$id' успешно удалено")
                                        }
                                    }
                                    put("isError", false)
                                }
                            } else {
                                buildJsonObject {
                                    putJsonArray("content") {
                                        addJsonObject {
                                            put("type", "text")
                                            put("text", "Напоминание с ID '$id' не найдено")
                                        }
                                    }
                                    put("isError", true)
                                }
                            }
                        }
                    }
                    "complete" -> {
                        val id = arguments?.get("id")?.jsonPrimitive?.content
                        if (id.isNullOrBlank()) {
                            buildJsonObject {
                                putJsonArray("content") {
                                    addJsonObject {
                                        put("type", "text")
                                        put("text", "Ошибка: требуется параметр 'id' для отметки напоминания как выполненного")
                                    }
                                }
                                put("isError", true)
                            }
                        } else {
                            val completed = reminderService.completeReminder(id)
                            if (completed) {
                                buildJsonObject {
                                    putJsonArray("content") {
                                        addJsonObject {
                                            put("type", "text")
                                            put("text", "Напоминание с ID '$id' отмечено как выполненное")
                                        }
                                    }
                                    put("isError", false)
                                }
                            } else {
                                buildJsonObject {
                                    putJsonArray("content") {
                                        addJsonObject {
                                            put("type", "text")
                                            put("text", "Напоминание с ID '$id' не найдено или уже выполнено")
                                        }
                                    }
                                    put("isError", true)
                                }
                            }
                        }
                    }
                    "get_summary" -> {
                        val summary = reminderService.getSummary()
                        buildJsonObject {
                            putJsonArray("content") {
                                addJsonObject {
                                    put("type", "text")
                                    put("text", summary)
                                }
                            }
                            put("isError", false)
                        }
                    }
                    "get_due" -> {
                        val dueReminders = reminderService.getDueReminders()
                        if (dueReminders.isEmpty()) {
                            buildJsonObject {
                                putJsonArray("content") {
                                    addJsonObject {
                                        put("type", "text")
                                        put("text", "Нет просроченных напоминаний")
                                    }
                                }
                                put("isError", false)
                            }
                        } else {
                            val formatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                                .withZone(java.time.ZoneId.systemDefault())
                            val listText = dueReminders.joinToString("\n\n") { reminder ->
                                buildString {
                                    append("ID: ${reminder.id}\n")
                                    append("Название: ${reminder.title}\n")
                                    if (reminder.description != null) {
                                        append("Описание: ${reminder.description}\n")
                                    }
                                    append("Напомнить: ${formatter.format(java.time.Instant.ofEpochMilli(reminder.dueDate!!))}\n")
                                    append("Приоритет: ${reminder.priority}")
                                }
                            }
                            buildJsonObject {
                                putJsonArray("content") {
                                    addJsonObject {
                                        put("type", "text")
                                        put("text", "Просроченные напоминания (${dueReminders.size}):\n\n$listText")
                                    }
                                }
                                put("isError", false)
                            }
                        }
                    }
                    else -> {
                        buildJsonObject {
                            putJsonArray("content") {
                                addJsonObject {
                                    put("type", "text")
                                    put("text", "Неизвестное действие: $action. Доступные действия: create, list, get, delete, complete, get_summary, get_due")
                                }
                            }
                            put("isError", true)
                        }
                    }
                }
                result
            } catch (e: Exception) {
                buildJsonObject {
                    putJsonArray("content") {
                        addJsonObject {
                            put("type", "text")
                            put("text", "Ошибка при работе с напоминаниями: ${e.message}")
                        }
                    }
                    put("isError", true)
                }
            }
        }
        
        "save_info" -> {
            try {
                val action = arguments?.get("action")?.jsonPrimitive?.content ?: ""
                
                val result = when (action) {
                    "save" -> {
                        val title = arguments?.get("title")?.jsonPrimitive?.content
                        val content = arguments?.get("content")?.jsonPrimitive?.content
                        val source = arguments?.get("source")?.jsonPrimitive?.content
                        val summary = arguments?.get("summary")?.jsonPrimitive?.content
                        val tags = arguments?.get("tags")?.jsonArray?.mapNotNull { it.jsonPrimitive.content } ?: emptyList()
                        val metadata = arguments?.get("metadata")?.jsonObject?.let { metaObj ->
                            metaObj.entries.associate { 
                                it.key to (it.value.jsonPrimitive.content)
                            }
                        } ?: emptyMap()
                        
                        if (title.isNullOrBlank() || content.isNullOrBlank()) {
                            buildJsonObject {
                                putJsonArray("content") {
                                    addJsonObject {
                                        put("type", "text")
                                        put("text", "Ошибка: требуются параметры 'title' и 'content' для сохранения информации")
                                    }
                                }
                                put("isError", true)
                            }
                        } else {
                            val info = InformationStorageService.save(
                                title = title,
                                content = content,
                                source = source,
                                summary = summary,
                                metadata = metadata,
                                tags = tags
                            )
                            buildJsonObject {
                                putJsonArray("content") {
                                    addJsonObject {
                                        put("type", "text")
                                        put("text", buildJsonObject {
                                            put("id", info.id)
                                            put("title", info.title)
                                            put("summary", info.summary ?: "")
                                            put("source", info.source ?: "")
                                            put("createdAt", info.createdAt)
                                            put("message", "Информация успешно сохранена")
                                        }.toString())
                                    }
                                }
                                put("isError", false)
                            }
                        }
                    }
                    "get" -> {
                        val id = arguments?.get("id")?.jsonPrimitive?.content
                        if (id.isNullOrBlank()) {
                            buildJsonObject {
                                putJsonArray("content") {
                                    addJsonObject {
                                        put("type", "text")
                                        put("text", "Ошибка: требуется параметр 'id' для получения информации")
                                    }
                                }
                                put("isError", true)
                            }
                        } else {
                            val info = InformationStorageService.getById(id)
                            if (info != null) {
                                buildJsonObject {
                                    putJsonArray("content") {
                                        addJsonObject {
                                            put("type", "text")
                                            put("text", Json { prettyPrint = true }.encodeToString(info))
                                        }
                                    }
                                    put("isError", false)
                                }
                            } else {
                                buildJsonObject {
                                    putJsonArray("content") {
                                        addJsonObject {
                                            put("type", "text")
                                            put("text", "Информация с ID '$id' не найдена")
                                        }
                                    }
                                    put("isError", true)
                                }
                            }
                        }
                    }
                    "search" -> {
                        val query = arguments?.get("query")?.jsonPrimitive?.content
                        if (query.isNullOrBlank()) {
                            buildJsonObject {
                                putJsonArray("content") {
                                    addJsonObject {
                                        put("type", "text")
                                        put("text", "Ошибка: требуется параметр 'query' для поиска")
                                    }
                                }
                                put("isError", true)
                            }
                        } else {
                            val results = InformationStorageService.searchByText(query)
                            buildJsonObject {
                                putJsonArray("content") {
                                    addJsonObject {
                                        put("type", "text")
                                        put("text", buildJsonObject {
                                            put("count", results.size)
                                            putJsonArray("results") {
                                                results.forEach { info ->
                                                    addJsonObject {
                                                        put("id", info.id)
                                                        put("title", info.title)
                                                        put("summary", info.summary ?: "")
                                                        put("source", info.source ?: "")
                                                        put("createdAt", info.createdAt)
                                                    }
                                                }
                                            }
                                        }.toString())
                                    }
                                }
                                put("isError", false)
                            }
                        }
                    }
                    "search_by_tags" -> {
                        val tags = arguments?.get("tags")?.jsonArray?.mapNotNull { it.jsonPrimitive.content } ?: emptyList()
                        if (tags.isEmpty()) {
                            buildJsonObject {
                                putJsonArray("content") {
                                    addJsonObject {
                                        put("type", "text")
                                        put("text", "Ошибка: требуется параметр 'tags' (массив) для поиска по тегам")
                                    }
                                }
                                put("isError", true)
                            }
                        } else {
                            val results = InformationStorageService.searchByTags(tags)
                            buildJsonObject {
                                putJsonArray("content") {
                                    addJsonObject {
                                        put("type", "text")
                                        put("text", buildJsonObject {
                                            put("count", results.size)
                                            putJsonArray("tags") {
                                                tags.forEach { add(it) }
                                            }
                                            putJsonArray("results") {
                                                results.forEach { info ->
                                                    addJsonObject {
                                                        put("id", info.id)
                                                        put("title", info.title)
                                                        put("summary", info.summary ?: "")
                                                        put("source", info.source ?: "")
                                                        putJsonArray("tags") {
                                                            info.tags.forEach { add(it) }
                                                        }
                                                        put("createdAt", info.createdAt)
                                                    }
                                                }
                                            }
                                        }.toString())
                                    }
                                }
                                put("isError", false)
                            }
                        }
                    }
                    "list" -> {
                        val allInfo = InformationStorageService.loadAll()
                        buildJsonObject {
                            putJsonArray("content") {
                                addJsonObject {
                                    put("type", "text")
                                    put("text", buildJsonObject {
                                        put("count", allInfo.size)
                                        putJsonArray("items") {
                                            allInfo.forEach { info ->
                                                addJsonObject {
                                                    put("id", info.id)
                                                    put("title", info.title)
                                                    put("summary", info.summary ?: "")
                                                    put("source", info.source ?: "")
                                                    put("createdAt", info.createdAt)
                                                }
                                            }
                                        }
                                    }.toString())
                                }
                            }
                            put("isError", false)
                        }
                    }
                    "stats" -> {
                        val stats = InformationStorageService.getStats()
                        buildJsonObject {
                            putJsonArray("content") {
                                addJsonObject {
                                    put("type", "text")
                                    put("text", Json { prettyPrint = true }.encodeToString(stats))
                                }
                            }
                            put("isError", false)
                        }
                    }
                    "delete" -> {
                        val id = arguments?.get("id")?.jsonPrimitive?.content
                        if (id.isNullOrBlank()) {
                            buildJsonObject {
                                putJsonArray("content") {
                                    addJsonObject {
                                        put("type", "text")
                                        put("text", "Ошибка: требуется параметр 'id' для удаления")
                                    }
                                }
                                put("isError", true)
                            }
                        } else {
                            val deleted = InformationStorageService.delete(id)
                            buildJsonObject {
                                putJsonArray("content") {
                                    addJsonObject {
                                        put("type", "text")
                                        put("text", if (deleted) {
                                            "Информация с ID '$id' успешно удалена"
                                        } else {
                                            "Информация с ID '$id' не найдена"
                                        })
                                    }
                                }
                                put("isError", !deleted)
                            }
                        }
                    }
                    else -> {
                        buildJsonObject {
                            putJsonArray("content") {
                                addJsonObject {
                                    put("type", "text")
                                    put("text", "Неизвестное действие: $action. Доступные: save, get, search, search_by_tags, list, stats, delete")
                                }
                            }
                            put("isError", true)
                        }
                    }
                }
                result
            } catch (e: Exception) {
                buildJsonObject {
                    putJsonArray("content") {
                        addJsonObject {
                            put("type", "text")
                            put("text", "Ошибка при работе с сохранением информации: ${e.message}")
                        }
                    }
                    put("isError", true)
                }
            }
        }
        
        else -> {
            buildJsonObject {
                putJsonArray("content") {
                    addJsonObject {
                        put("type", "text")
                        put("text", "Unknown tool: $toolName")
                    }
                }
                put("isError", true)
            }
        }
    }
    
    return buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", request.id)
        put("result", result)
    }
}

/**
 * Простой калькулятор выражений (только для демонстрации)
 * В продакшене используйте безопасный парсер выражений
 */
fun evaluateExpression(expression: String): Double {
    // Удаляем пробелы
    val cleanExpr = expression.replace(" ", "")
    
    // Простая проверка на безопасность (только цифры и базовые операторы)
    if (!cleanExpr.matches(Regex("""^[\d+\-*/().\s]+$"""))) {
        throw IllegalArgumentException("Invalid expression")
    }
    
    // Используем JavaScript engine для простоты (в продакшене используйте безопасный парсер)
    return try {
        // Простая реализация для демонстрации
        when {
            cleanExpr.contains("+") -> {
                val parts = cleanExpr.split("+")
                parts.map { it.toDouble() }.sum()
            }
            cleanExpr.contains("-") -> {
                val parts = cleanExpr.split("-")
                parts[0].toDouble() - parts.drop(1).sumOf { it.toDouble() }
            }
            cleanExpr.contains("*") -> {
                val parts = cleanExpr.split("*")
                parts.map { it.toDouble() }.reduce { a, b -> a * b }
            }
            cleanExpr.contains("/") -> {
                val parts = cleanExpr.split("/")
                parts.map { it.toDouble() }.reduce { a, b -> a / b }
            }
            else -> cleanExpr.toDouble()
        }
    } catch (e: Exception) {
        throw IllegalArgumentException("Cannot evaluate expression: $expression")
    }
}

/**
 * Обрабатывает resources/list запрос
 */
fun handleResourcesList(request: McpRequest): JsonObject {
    return buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", request.id)
        putJsonObject("result") {
            putJsonArray("resources") {
                addJsonObject {
                    put("uri", "file:///example.txt")
                    put("name", "Example Resource")
                    put("description", "An example text resource")
                    put("mimeType", "text/plain")
                }
                addJsonObject {
                    put("uri", "file:///server-info.json")
                    put("name", "Server Information")
                    put("description", "Information about this MCP server")
                    put("mimeType", "application/json")
                }
            }
        }
    }
}

/**
 * Обрабатывает resources/read запрос
 */
fun handleResourceRead(request: McpRequest, requestBody: String): JsonObject {
    val json = Json { ignoreUnknownKeys = true }
    val requestJson = json.parseToJsonElement(requestBody).jsonObject
    val params = requestJson["params"]?.jsonObject
    val uri = params?.get("uri")?.jsonPrimitive?.content ?: ""
    
    val content = when (uri) {
        "file:///example.txt" -> "This is an example resource content.\nIt demonstrates how MCP resources work."
        "file:///server-info.json" -> """{"name":"local-mcp-server","version":"1.0.0","status":"running"}"""
        else -> "Resource not found: $uri"
    }
    
    return buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", request.id)
        putJsonObject("result") {
            put("uri", uri)
            put("mimeType", if (uri.endsWith(".json")) "application/json" else "text/plain")
            put("text", content)
        }
    }
}

/**
 * Создает ответ с ошибкой
 */
fun createErrorResponse(id: Int, code: Int, message: String): JsonObject {
    return buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        putJsonObject("error") {
            put("code", code)
            put("message", message)
        }
    }
}

// Данные для Яндекс Трекера (мок данные для демонстрации)
data class YandexTrackerTask(
    val key: String,
    val summary: String,
    val status: String,
    val description: String? = null,
    val assignee: String? = null
)

// Хранилище задач (в реальном приложении это будет подключение к API Яндекс Трекера)
private val mockTasks = mutableListOf(
    YandexTrackerTask("TEST-1", "Исправить баг в авторизации", "open", "Пользователи не могут войти в систему"),
    YandexTrackerTask("TEST-2", "Добавить новую функцию поиска", "open", "Реализовать полнотекстовый поиск"),
    YandexTrackerTask("TEST-3", "Обновить документацию API", "inProgress", "Обновить swagger документацию"),
    YandexTrackerTask("TEST-4", "Оптимизировать запросы к БД", "open", "Улучшить производительность"),
    YandexTrackerTask("TEST-5", "Добавить тесты для модуля платежей", "open", "Покрыть тестами критический функционал"),
    YandexTrackerTask("TEST-6", "Исправить ошибку валидации", "resolved", "Исправлена валидация email"),
    YandexTrackerTask("TEST-7", "Настроить CI/CD", "open", "Автоматизировать деплой")
)

/**
 * Получает список открытых задач
 */
fun getOpenTasks(queue: String): List<YandexTrackerTask> {
    return mockTasks.filter { it.status == "open" }
}

/**
 * Подсчитывает количество открытых задач
 */
fun countOpenTasks(queue: String): Int {
    return mockTasks.count { it.status == "open" }
}

/**
 * Получает задачу по ID
 */
fun getTaskById(taskId: String, queue: String): YandexTrackerTask? {
    return mockTasks.find { it.key == taskId }
}

/**
 * Создает новую задачу в трекере
 */
fun createTask(summary: String, description: String? = null, queue: String = "TEST"): YandexTrackerTask {
    // Генерируем новый ключ задачи
    val nextNumber = mockTasks.size + 1
    val newKey = "$queue-$nextNumber"
    
    val newTask = YandexTrackerTask(
        key = newKey,
        summary = summary,
        status = "open",
        description = description,
        assignee = null
    )
    
    // Добавляем задачу в список
    mockTasks.add(newTask)
    
    println("Created new task: $newKey - $summary")
    
    return newTask
}

// MCP Request структура
@Serializable
data class McpRequest(
    val jsonrpc: String,
    val id: Int,
    val method: String,
    val params: JsonElement? = null
)


