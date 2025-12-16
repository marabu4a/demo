package org.example.demo.chat

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.*
import io.ktor.client.plugins.timeout
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * MCP (Model Context Protocol) клиент для подключения к remote MCP серверам
 */
@OptIn(ExperimentalAtomicApi::class)
class McpClient(
    private val httpClient: HttpClient,
    val serverUrl: String // Делаем публичным для доступа из менеджера
) {
    companion object {
        private const val TAG = "McpClient"
    }
    
    private var sessionId: String? = null
    @OptIn(ExperimentalAtomicApi::class)
    private val messageIdCounter = AtomicInt(1)
    
    // SSE соединение
    private var sseJob: Job? = null
    private val messageFlow = MutableSharedFlow<McpMessage>(replay = 0, extraBufferCapacity = 64)
    
    /**
     * Подключается к MCP серверу
     */
    suspend fun connect(): Boolean = withContext(Dispatchers.Default) {
        try {
            AppLogger.d(TAG, "Connecting to MCP server: $serverUrl")
            
            // Проверяем доступность сервера (опционально)
            if (!isServerAvailable()) {
                AppLogger.w(TAG, "MCP server may not be available, but continuing with connection attempt")
            }
            
            // Инициализируем сессию
            val initResponse = initialize()
            if (initResponse == null) {
                AppLogger.e(TAG, "Failed to initialize MCP session")
                return@withContext false
            }
            
            // sessionId может приходить в заголовках ответа или в теле ответа
            sessionId = initResponse.sessionId ?: "session_${currentTimeMillis()}"
            AppLogger.d(TAG, "MCP session initialized: $sessionId")
            
            // Запускаем SSE соединение для получения сообщений (если поддерживается)
            startSseConnection()
            
            AppLogger.i(TAG, "Successfully connected to MCP server at $serverUrl")
            true
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to connect to MCP server: ${e.message}", e)
            false
        }
    }
    
    /**
     * Проверяет доступность сервера (опциональная проверка)
     */
    private suspend fun isServerAvailable(): Boolean {
        return try {
            // Пытаемся сделать простой запрос для проверки доступности
            val endpoint = when {
                serverUrl.endsWith("/message") -> serverUrl.replace("/message", "/health")
                serverUrl.endsWith("/sse") -> serverUrl.replace("/sse", "/health")
                serverUrl.endsWith("/") -> "${serverUrl}health"
                else -> "$serverUrl/health"
            }
            
            val response = httpClient.get(endpoint)
            val available = response.status.value in 200..299
            if (available) {
                AppLogger.d(TAG, "MCP server health check passed")
            } else {
                AppLogger.d(TAG, "MCP server health check returned: ${response.status.value}")
            }
            available
        } catch (e: Exception) {
            // Если health endpoint недоступен, это не критично
            AppLogger.d(TAG, "Health check failed (not critical): ${e.message}")
            true // Продолжаем подключение даже если health check не прошел
        }
    }
    
    /**
     * Инициализирует сессию с MCP сервером
     */
    private suspend fun initialize(): McpInitializeResponse? {
        val json = Json { ignoreUnknownKeys = true }
        val params = json.encodeToJsonElement(
            McpInitializeParams(
                protocolVersion = "2024-11-05",
                capabilities = McpCapabilities(),
                clientInfo = McpClientInfo(
                    name = "demo-chat",
                    version = "1.0.0"
                )
            )
        )
        
        val request = McpRequest(
            jsonrpc = "2.0",
            id = messageIdCounter.fetchAndAdd(1),
            method = "initialize",
            params = params
        )
        
        return sendRequest<McpInitializeResponse>(request)
    }
    
    /**
     * Отправляет запрос к MCP серверу
     * Пробует несколько вариантов endpoint'ов, если первый не работает
     */
    private suspend fun <T> sendRequest(request: McpRequest): T? {
        val json = Json { ignoreUnknownKeys = true }
        val requestBody = json.encodeToString(McpRequest.serializer(), request)
        
        AppLogger.d(TAG, "Sending MCP request to $serverUrl: ${request.method}, id: ${request.id}")
        AppLogger.d(TAG, "Request body: $requestBody")
        
        // Список возможных endpoint'ов для попытки подключения
        val endpointsToTry = buildList {
            val baseUrl = serverUrl.trimEnd('/')
            
            // Если URL уже содержит endpoint, используем его первым
            if (serverUrl.endsWith("/message") || serverUrl.endsWith("/sse") || serverUrl.endsWith("/mcp")) {
                add(serverUrl)
            }
            
            // Пробуем различные варианты endpoint'ов
            add("$baseUrl/message")  // Стандартный endpoint для запросов
            add("$baseUrl/mcp/message")  // С /mcp префиксом
            add("$baseUrl/mcp")  // С /mcp но без /message
            add(baseUrl)  // Базовый URL (некоторые серверы принимают запросы напрямую)
            add("$baseUrl/api/mcp")  // С /api/mcp префиксом
            add("$baseUrl/v1/mcp")  // С версионированием
        }.distinct()
        
        AppLogger.d(TAG, "Trying ${endpointsToTry.size} endpoint variants: ${endpointsToTry.joinToString(", ")}")
        
        // Пробуем каждый endpoint
        for (endpoint in endpointsToTry) {
            try {
                AppLogger.d(TAG, "Trying endpoint: $endpoint")
                val result = trySendRequestToEndpoint<T>(request, requestBody, endpoint)
                if (result != null) {
                    AppLogger.i(TAG, "Successfully connected using endpoint: $endpoint")
                    return result
                }
            } catch (e: Exception) {
                AppLogger.d(TAG, "Endpoint $endpoint failed: ${e.message}")
                // Продолжаем пробовать следующий endpoint
            }
        }
        
        AppLogger.e(TAG, "All endpoint variants failed for $serverUrl")
        return null
    }
    
    /**
     * Пытается отправить запрос к конкретному endpoint'у
     */
    private suspend fun <T> trySendRequestToEndpoint(
        request: McpRequest,
        requestBody: String,
        endpoint: String
    ): T? {
        return try {
            
            val json = Json { ignoreUnknownKeys = true }
            
            val response = httpClient.post(endpoint) {
                header(HttpHeaders.ContentType, ContentType.Application.Json)
                header(HttpHeaders.Accept, "application/json")
                header(HttpHeaders.UserAgent, "demo-chat-mcp-client/1.0.0")
                sessionId?.let { 
                    header("X-Session-Id", it)
                    AppLogger.d(TAG, "Sending with session ID: $it")
                }
                setBody(requestBody)
                
                // Увеличиваем таймауты для remote серверов
                timeout {
                    requestTimeoutMillis = 30000
                    connectTimeoutMillis = 10000
                    socketTimeoutMillis = 30000
                }
            }
            
            // Проверяем sessionId в заголовках ответа
            response.headers["X-Session-Id"]?.let { 
                if (sessionId == null || sessionId != it) {
                    sessionId = it
                    AppLogger.d(TAG, "Session ID received from headers: $it")
                }
            }
            
            val statusCode = response.status.value
            AppLogger.d(TAG, "MCP response status: $statusCode for endpoint: $endpoint")
            
            if (statusCode !in 200..299) {
                val errorBody = try {
                    response.body<String>()
                } catch (e: Exception) {
                    "Unable to read error body: ${e.message}"
                }
                AppLogger.d(TAG, "MCP request failed for $endpoint: $statusCode, body: $errorBody")
                return null
            }
            
            val responseBody = response.body<String>()
            AppLogger.d(TAG, "MCP response body length: ${responseBody.length}")
            AppLogger.d(TAG, "MCP response body: $responseBody")
            
            // Проверяем, что ответ - валидный JSON
            if (responseBody.isBlank()) {
                AppLogger.d(TAG, "Empty response body from endpoint: $endpoint")
                return null
            }
            
            val jsonResponse = try {
                json.decodeFromString<JsonObject>(responseBody)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to parse JSON response from $endpoint: ${e.message}")
                AppLogger.d(TAG, "Response was: $responseBody")
                return null
            }
            
            if (jsonResponse["error"] != null) {
                val error = jsonResponse["error"]!!.jsonObject
                val errorMessage = error["message"]?.jsonPrimitive?.content ?: "Unknown error"
                val errorCode = error["code"]?.jsonPrimitive?.contentOrNull
                AppLogger.d(TAG, "MCP error for $endpoint: $errorMessage (code: $errorCode)")
                return null
            }
            
            val result = jsonResponse["result"]
            if (result != null) {
                // Пытаемся десериализовать в зависимости от типа запроса
                try {
                    when (request.method) {
                        "initialize" -> {
                            @Suppress("UNCHECKED_CAST")
                            return json.decodeFromJsonElement(McpInitializeResponse.serializer(), result) as? T
                        }
                        "resources/list" -> {
                            @Suppress("UNCHECKED_CAST")
                            return json.decodeFromJsonElement(McpResourcesListResponse.serializer(), result) as? T
                        }
                        "tools/list" -> {
                            @Suppress("UNCHECKED_CAST")
                            return json.decodeFromJsonElement(McpToolsListResponse.serializer(), result) as? T
                        }
                        "tools/call" -> {
                            @Suppress("UNCHECKED_CAST")
                            return json.decodeFromJsonElement(McpToolResult.serializer(), result) as? T
                        }
                        "resources/read" -> {
                            @Suppress("UNCHECKED_CAST")
                            return json.decodeFromJsonElement(McpResourceContent.serializer(), result) as? T
                        }
                        else -> {
                            AppLogger.w(TAG, "Unknown MCP method: ${request.method}, trying generic deserialization")
                            // Пытаемся десериализовать как JsonObject
                            @Suppress("UNCHECKED_CAST")
                            return result as? T
                        }
                    }
                } catch (e: Exception) {
                    AppLogger.d(TAG, "Failed to deserialize MCP response for method ${request.method}: ${e.message}")
                    return null
                }
            } else {
                AppLogger.d(TAG, "MCP response has no result field for endpoint: $endpoint")
                return null
            }
        } catch (e: Exception) {
            AppLogger.d(TAG, "Exception for endpoint $endpoint: ${e.message}")
            throw e // Пробрасываем исключение, чтобы попробовать следующий endpoint
        }
    }
    
    /**
     * Запускает SSE соединение для получения сообщений от сервера
     */
    private fun startSseConnection() {
        // Отменяем предыдущее соединение, если оно есть
        sseJob?.cancel()
        
        sseJob = CoroutineScope(Dispatchers.Default).launch {
            try {
                AppLogger.d(TAG, "Starting SSE connection...")
                
                // Определяем SSE endpoint
                val sseEndpoint = when {
                    serverUrl.endsWith("/sse") -> serverUrl
                    serverUrl.endsWith("/message") -> serverUrl.replace("/message", "/sse")
                    serverUrl.endsWith("/") -> "${serverUrl}sse"
                    else -> "$serverUrl/sse"
                }
                
                AppLogger.d(TAG, "SSE endpoint: $sseEndpoint")
                
                // Открываем SSE соединение
                val response = httpClient.get(sseEndpoint) {
                    header(HttpHeaders.Accept, "text/event-stream")
                    header(HttpHeaders.CacheControl, "no-cache")
                    sessionId?.let { 
                        header("X-Session-Id", it)
                        AppLogger.d(TAG, "SSE request with session ID: $it")
                    }
                }
                
                if (response.status.value !in 200..299) {
                    AppLogger.w(TAG, "SSE connection failed with status: ${response.status.value}")
                    AppLogger.d(TAG, "Falling back to polling mode (SSE not available)")
                    return@launch
                }
                
                AppLogger.i(TAG, "SSE connection established, reading stream...")
                
                // Читаем SSE поток построчно
                val channel = response.bodyAsChannel()
                
                while (isActive) {
                    try {
                        // Читаем строку из канала (до символа новой строки)
                        val line = channel.readUTF8Line()
                        
                        if (line == null) {
                            // Конец потока
                            AppLogger.d(TAG, "SSE stream ended")
                            break
                        }
                        
                        val trimmedLine = line.trim()
                        
                        if (trimmedLine.isEmpty()) {
                            // Пустая строка означает конец события в SSE формате
                            continue
                        }
                        
                        // Парсим SSE формат: "data: {...}" или "event: ..."
                        when {
                            trimmedLine.startsWith("data: ") -> {
                                val jsonData = trimmedLine.substring(6) // Убираем "data: "
                                processSseEvent(jsonData)
                            }
                            trimmedLine.startsWith("event: ") -> {
                                val eventType = trimmedLine.substring(7) // Убираем "event: "
                                AppLogger.d(TAG, "SSE event type: $eventType")
                            }
                            trimmedLine.startsWith("id: ") -> {
                                val eventId = trimmedLine.substring(4) // Убираем "id: "
                                AppLogger.d(TAG, "SSE event ID: $eventId")
                            }
                            trimmedLine.startsWith("retry: ") -> {
                                val retryMs = trimmedLine.substring(7).toIntOrNull()
                                AppLogger.d(TAG, "SSE retry interval: ${retryMs}ms")
                            }
                            else -> {
                                // Если строка не начинается с префикса, считаем её данными
                                processSseEvent(trimmedLine)
                            }
                        }
                    } catch (e: Exception) {
                        if (isActive) {
                            AppLogger.e(TAG, "Error reading SSE stream: ${e.message}", e)
                            // Пытаемся переподключиться через некоторое время
                            delay(5000)
                            break // Выходим из цикла, чтобы переподключиться
                        }
                    }
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "SSE connection error: ${e.message}", e)
                AppLogger.d(TAG, "Falling back to polling mode")
            }
        }
    }
    
    /**
     * Обрабатывает SSE событие (JSON данные)
     */
    private suspend fun processSseEvent(jsonData: String) {
        try {
            val json = Json { ignoreUnknownKeys = true }
            val message = json.decodeFromString<McpMessage>(jsonData)
            
            AppLogger.d(TAG, "Received SSE message: ${message.method ?: "notification"}")
            
            // Отправляем сообщение в поток
            messageFlow.emit(message)
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to parse SSE event: ${e.message}")
            AppLogger.d(TAG, "Raw SSE data: $jsonData")
        }
    }
    
    /**
     * Получает список доступных ресурсов
     */
    suspend fun listResources(): List<McpResource> {
        val request = McpRequest(
            jsonrpc = "2.0",
            id = messageIdCounter.fetchAndAdd(1),
            method = "resources/list",
            params = null
        )
        
        val response = sendRequest<McpResourcesListResponse>(request)
        return response?.resources ?: emptyList()
    }
    
    /**
     * Получает список доступных инструментов
     */
    suspend fun listTools(): List<McpTool> {
        val request = McpRequest(
            jsonrpc = "2.0",
            id = messageIdCounter.fetchAndAdd(1),
            method = "tools/list",
            params = null
        )
        
        val response = sendRequest<McpToolsListResponse>(request)
        return response?.tools ?: emptyList()
    }
    
    /**
     * Вызывает инструмент MCP сервера
     */
    suspend fun callTool(name: String, arguments: JsonObject? = null): McpToolResult? {
        val json = Json { ignoreUnknownKeys = true }
        val params = json.encodeToJsonElement(
            McpToolCallParams(
                name = name,
                arguments = arguments
            )
        )
        
        val request = McpRequest(
            jsonrpc = "2.0",
            id = messageIdCounter.fetchAndAdd(1),
            method = "tools/call",
            params = params
        )
        
        return sendRequest<McpToolResult>(request)
    }
    
    /**
     * Получает ресурс
     */
    suspend fun getResource(uri: String): McpResourceContent? {
        val json = Json { ignoreUnknownKeys = true }
        val params = json.encodeToJsonElement(McpResourceReadParams(uri = uri))
        
        val request = McpRequest(
            jsonrpc = "2.0",
            id = messageIdCounter.fetchAndAdd(1),
            method = "resources/read",
            params = params
        )
        
        return sendRequest<McpResourceContent>(request)
    }
    
    /**
     * Отключается от сервера
     */
    fun disconnect() {
        sseJob?.cancel()
        sessionId = null
        AppLogger.d(TAG, "Disconnected from MCP server")
    }
    
    /**
     * Получает поток сообщений от сервера через SSE
     * Используйте этот метод для подписки на уведомления от MCP сервера
     * 
     * Пример использования:
     * ```
     * val client = McpClient(httpClient, serverUrl)
     * client.connect()
     * 
     * client.getMessageFlow().collect { message ->
     *     println("Received: ${message.method}")
     * }
     * ```
     */
    fun getMessageFlow(): Flow<McpMessage> = messageFlow.asSharedFlow()
    
    /**
     * Проверяет, активно ли SSE соединение
     */
    fun isSseConnected(): Boolean = sseJob?.isActive == true
}

// MCP протокол структуры данных

@Serializable
data class McpRequest(
    val jsonrpc: String,
    val id: Int,
    val method: String,
    val params: JsonElement? = null
)

@Serializable
data class McpInitializeParams(
    val protocolVersion: String,
    val capabilities: McpCapabilities,
    val clientInfo: McpClientInfo
)

@Serializable
data class McpCapabilities(
    val experimental: JsonObject? = null
)

@Serializable
data class McpClientInfo(
    val name: String,
    val version: String
)

@Serializable
data class McpInitializeResponse(
    val protocolVersion: String,
    val capabilities: McpCapabilities? = null,
    val serverInfo: McpServerInfo? = null,
    val sessionId: String? = null // Будет установлен сервером в заголовках или ответе
)

@Serializable
data class McpServerInfo(
    val name: String,
    val version: String
)

@Serializable
data class McpMessage(
    val jsonrpc: String? = null,
    val method: String? = null,
    val params: JsonObject? = null
)

@Serializable
data class McpResource(
    val uri: String,
    val name: String,
    val description: String? = null,
    val mimeType: String? = null
)

@Serializable
data class McpResourcesListResponse(
    val resources: List<McpResource>
)

@Serializable
data class McpResourceReadParams(
    val uri: String
)

@Serializable
data class McpResourceContent(
    val uri: String,
    val mimeType: String? = null,
    val text: String? = null,
    val blob: String? = null
)

@Serializable
data class McpTool(
    val name: String,
    val description: String? = null,
    val inputSchema: JsonObject? = null
)

@Serializable
data class McpToolsListResponse(
    val tools: List<McpTool>
)

@Serializable
data class McpToolCallParams(
    val name: String,
    val arguments: JsonObject? = null
)

@Serializable
data class McpToolResult(
    val content: List<McpToolContent>,
    val isError: Boolean = false
)

@Serializable
data class McpToolContent(
    val type: String,
    val text: String? = null
)

// Менеджер для управления несколькими MCP серверами
class McpClientManager(
    private val httpClient: HttpClient
) {
    companion object {
        private const val TAG = "McpClientManager"
    }
    
    private val clients = mutableMapOf<String, McpClient>()
    
    /**
     * Подключает MCP сервер по URL
     */
    suspend fun connectServer(name: String, url: String): Boolean {
        // Нормализуем URL (убираем лишние слэши)
        val normalizedUrl = url.trimEnd('/')
        
        if (clients.containsKey(name)) {
            val existingClient = clients[name]
            // Если URL изменился, переподключаемся
            if (existingClient?.serverUrl != normalizedUrl) {
                AppLogger.d(TAG, "URL changed for server $name, reconnecting...")
                disconnectServer(name)
            } else {
                AppLogger.w(TAG, "Server $name already connected")
                return true
            }
        }
        
        val client = McpClient(httpClient, normalizedUrl)
        val connected = client.connect()
        
        if (connected) {
            clients[name] = client
            AppLogger.i(TAG, "Connected to MCP server: $name at $normalizedUrl")
        } else {
            AppLogger.e(TAG, "Failed to connect to MCP server: $name at $normalizedUrl")
        }
        
        return connected
    }
    
    /**
     * Подключает несколько MCP серверов из списка URL
     * Формат: список пар "имя:URL" или просто список URL (имя будет сгенерировано)
     * 
     * URL автоматически нормализуется:
     * - Если URL не содержит /mcp или /sse, клиент попробует оба варианта
     * - Убираются лишние слэши
     */
    suspend fun connectServersFromList(serverList: List<String>): Map<String, Boolean> {
        val results = mutableMapOf<String, Boolean>()
        
        serverList.forEach { serverEntry ->
            val (name, rawUrl) = when {
                serverEntry.contains(":") && !serverEntry.startsWith("http") -> {
                    // Формат "имя:URL"
                    val parts = serverEntry.split(":", limit = 2)
                    Pair(parts[0].trim(), parts[1].trim())
                }
                else -> {
                    // Просто URL, генерируем имя из домена
                    val urlParts = serverEntry.replace(Regex("^https?://"), "").split("/")
                    val domain = urlParts.firstOrNull() ?: "server"
                    val name = domain.replace(Regex("[^a-zA-Z0-9]"), "_")
                    Pair(name, serverEntry.trim())
                }
            }
            
            // Нормализуем URL - убираем лишние слэши, но сохраняем структуру
            val normalizedUrl = normalizeMcpUrl(rawUrl)
            
            AppLogger.d(TAG, "Connecting server '$name' with URL: $normalizedUrl")
            val connected = connectServer(name, normalizedUrl)
            results[name] = connected
        }
        
        return results
    }
    
    /**
     * Нормализует URL MCP сервера
     * Если URL не содержит /mcp или /sse, возвращает как есть (клиент сам определит endpoint)
     */
    private fun normalizeMcpUrl(url: String): String {
        var normalized = url.trim().trimEnd('/')
        
        // Если URL уже содержит /mcp или /sse, оставляем как есть
        if (normalized.contains("/mcp") || normalized.contains("/sse")) {
            return normalized
        }
        
        // Иначе возвращаем базовый URL - клиент сам попробует /mcp и /sse
        return normalized
    }
    
    /**
     * Отключает MCP сервер
     */
    fun disconnectServer(name: String) {
        clients[name]?.disconnect()
        clients.remove(name)
        AppLogger.i(TAG, "Disconnected from MCP server: $name")
    }
    
    /**
     * Получает клиент по имени
     */
    fun getClient(name: String): McpClient? = clients[name]
    
    /**
     * Получает список подключенных серверов
     */
    fun getConnectedServers(): List<String> = clients.keys.toList()
    
    /**
     * Получает информацию о всех серверах (имя и URL)
     */
    fun getAllServersInfo(): Map<String, String> {
        return clients.mapValues { it.value.serverUrl }
    }
    
    /**
     * Отключает все серверы
     */
    fun disconnectAll() {
        clients.values.forEach { it.disconnect() }
        clients.clear()
        AppLogger.i(TAG, "All MCP servers disconnected")
    }
}
