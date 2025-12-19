package org.example.demo.chat

import androidx.compose.runtime.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlin.random.Random

enum class DialogueMode {
    NORMAL,      // Обычный режим - все сообщения хранятся
    SUMMARY      // Режим сжатия - каждые N сообщений создается summary
}

class ChatViewModel(
    private val apiClientManager: AiApiClientManager,
    private val mcpClientManager: McpClientManager? = null
) {
    companion object {
        private const val TAG = "ChatViewModel"
        private const val COMPRESSION_THRESHOLD = 10 // Сжимать каждые 10 сообщений (всех типов)
    }
    
    private val viewModelScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        // Проверяем наличие сохраненной истории при инициализации
        viewModelScope.launch {
            if (_session.value.messages.isEmpty()) {
                checkForSavedHistory()
            }
        }
        
        // Автоматически подключаемся к MCP серверам при старте
        viewModelScope.launch {
            // Подключение к локальному MCP серверу
            try {
                val localServerUrl = "http://localhost:8080/mcp"
                AppLogger.d(TAG, "Attempting to auto-connect to local MCP server: $localServerUrl")
                
                mcpClientManager?.let { manager ->
                    val connected = manager.connectServer("local", localServerUrl)
                    if (connected) {
                        val updatedServers = _mcpServers.value.toMutableList()
                        val existingIndex = updatedServers.indexOfFirst { it.name == "local" }
                        if (existingIndex >= 0) {
                            updatedServers[existingIndex] = McpServerConfig("local", localServerUrl, true)
                        } else {
                            updatedServers.add(McpServerConfig("local", localServerUrl, true))
                        }
                        _mcpServers.value = updatedServers
                        AppLogger.i(TAG, "Auto-connected to local MCP server")
                    } else {
                        AppLogger.w(TAG, "Failed to auto-connect to local MCP server (server may not be running)")
                    }
                }
            } catch (e: Exception) {
                AppLogger.w(TAG, "Error during auto-connect to local MCP server: ${e.message}")
                // Не критично, продолжаем работу
            }
            
            // Подключение к удаленному MCP серверу fetch
            try {
                val remoteFetchServerUrl = "https://remote.mcpservers.org/fetch/mcp"
                AppLogger.d(TAG, "Attempting to auto-connect to remote fetch MCP server: $remoteFetchServerUrl")
                
                mcpClientManager?.let { manager ->
                    val connected = manager.connectServer("remote_fetch", remoteFetchServerUrl)
                    if (connected) {
                        val updatedServers = _mcpServers.value.toMutableList()
                        val existingIndex = updatedServers.indexOfFirst { it.name == "remote_fetch" }
                        if (existingIndex >= 0) {
                            updatedServers[existingIndex] = McpServerConfig("remote_fetch", remoteFetchServerUrl, true)
                        } else {
                            updatedServers.add(McpServerConfig("remote_fetch", remoteFetchServerUrl, true))
                        }
                        _mcpServers.value = updatedServers
                        AppLogger.i(TAG, "Auto-connected to remote fetch MCP server")
                    } else {
                        AppLogger.w(TAG, "Failed to auto-connect to remote fetch MCP server (server may be unavailable)")
                    }
                }
            } catch (e: Exception) {
                AppLogger.w(TAG, "Error during auto-connect to remote fetch MCP server: ${e.message}")
                // Не критично, продолжаем работу
            }
        }
    }
    
    private val _session = mutableStateOf(ChatSession())
    val session: State<ChatSession> = _session
    
    private val _isLoading = mutableStateOf(false)
    val isLoading: State<Boolean> = _isLoading
    
    private val _errorMessage = mutableStateOf<String?>(null)
    val errorMessage: State<String?> = _errorMessage
    
    private val _accessToken = mutableStateOf("")
    val accessToken: State<String> = _accessToken
    
    private val _isLoadingToken = mutableStateOf(false)
    val isLoadingToken: State<Boolean> = _isLoadingToken
    
    private val _useSystemRole = mutableStateOf(false)
    val useSystemRole: State<Boolean> = _useSystemRole
    
    private val _temperature = mutableStateOf(0.0f)
    val temperature: State<Float> = _temperature
    
    private val _maxTokens = mutableStateOf<Int?>(null)
    val maxTokens: State<Int?> = _maxTokens
    
    private val _selectedModel = mutableStateOf(AiModel.GIGACHAT)
    val selectedModel: State<AiModel> = _selectedModel
    
    private val _huggingFaceToken = mutableStateOf("")
    val huggingFaceToken: State<String> = _huggingFaceToken
    
    private val _dialogueMode = mutableStateOf(DialogueMode.NORMAL)
    val dialogueMode: State<DialogueMode> = _dialogueMode
    
    // Состояние для диалога загрузки истории
    private val _showLoadHistoryDialog = mutableStateOf(false)
    val showLoadHistoryDialog: State<Boolean> = _showLoadHistoryDialog
    
    private var _pendingHistoryModel: String? = null
    
    // Состояние для MCP серверов
    data class McpServerConfig(
        val name: String,
        val url: String,
        val connected: Boolean = false
    )
    
    private val _mcpServers = mutableStateOf<List<McpServerConfig>>(emptyList())
    val mcpServers: State<List<McpServerConfig>> = _mcpServers
    
    private val _showMcpServerDialog = mutableStateOf(false)
    val showMcpServerDialog: State<Boolean> = _showMcpServerDialog
    
    // Храним сжатое резюме истории отдельно (не показывается в UI, используется в запросах)
    // Вместе с summary храним количество сжатых сообщений пользователя
    private data class CompressedSummary(
        val summary: String,
        val compressedUserMessagesCount: Int // Количество сообщений пользователя, сжатых в summary
    )
    private var _compressedSummary: CompressedSummary? = null
    
    // Храним ID всех сообщений (пользователя и AI), которые уже сжаты в summary (чтобы исключить их из запросов)
    private var _compressedMessageIds: Set<String> = emptySet()
    
    // Храним загруженную историю (не показывается в UI, используется только в запросах)
    private var _loadedHistory: List<Message>? = null
    private var _loadedHistoryUserMessagesCount: Int = 0 // Количество сообщений пользователя в загруженной истории
    
    fun toggleSystemRole() {
        _useSystemRole.value = !_useSystemRole.value
        AppLogger.d(TAG, "System role mode: ${_useSystemRole.value}")
    }
    
    fun setTemperature(value: Float) {
        _temperature.value = value
        AppLogger.d(TAG, "Temperature set to: $value")
    }
    
    fun setMaxTokens(value: Int?) {
        _maxTokens.value = value
        AppLogger.d(TAG, "Max tokens set to: $value")
    }
    
    fun setSelectedModel(model: AiModel) {
        // Сохраняем историю для предыдущей модели перед переключением
        if (_session.value.messages.isNotEmpty()) {
            saveHistory()
        }
        
        _selectedModel.value = model
        AppLogger.d(TAG, "Model changed to: ${model.displayName}")
        
        val client = apiClientManager.getClient(model)
        
        // При переключении на GigaChat
        if (model.type == AiModelType.GIGACHAT) {
            if (_accessToken.value.isBlank()) {
                // Если токен пустой, пытаемся получить его
                getAccessTokenFromKey()
            } else {
                // Если токен уже есть, устанавливаем его в клиенте
                val gigaChatClient = client as? GigaChatApiClient
                gigaChatClient?.setAccessToken(_accessToken.value)
            }
        }
        
        // При переключении на HuggingFace
        if (model.type == AiModelType.HUGGINGFACE) {
            val huggingFaceClient = client as? HuggingFaceApiClient
            if (_huggingFaceToken.value.isNotBlank()) {
                huggingFaceClient?.setApiToken(_huggingFaceToken.value)
            } else {
                // Пытаемся использовать встроенный токен
                viewModelScope.launch {
                    try {
                        val token = huggingFaceClient?.getAccessToken() ?: ""
                        if (token.isNotBlank()) {
                            setHuggingFaceToken(token)
                        } else {
                            _accessToken.value = ""
                        }
                    } catch (e: Exception) {
                        AppLogger.d(TAG, "Could not get built-in HuggingFace token: ${e.message}")
                        _accessToken.value = ""
                    }
                }
            }
        }
        
        // Проверяем наличие сохраненной истории для новой модели
        if (_session.value.messages.isEmpty()) {
            checkForSavedHistory()
        }
    }
    
    fun setHuggingFaceToken(token: String) {
        _huggingFaceToken.value = token
        val client = apiClientManager.getClient(_selectedModel.value) as? HuggingFaceApiClient
        client?.setApiToken(token)
        AppLogger.d(TAG, "HuggingFace token set")
    }
    
    fun setAccessToken(token: String, expiresAt: Long? = null) {
        _accessToken.value = token
        val client = apiClientManager.getClient(_selectedModel.value)
        if (client is GigaChatApiClient) {
            client.setAccessToken(token, expiresAt)
        }
    }
    
    fun getAccessTokenFromKey() {
        _isLoadingToken.value = true
        _errorMessage.value = null
        
        viewModelScope.launch {
            try {
                val client = apiClientManager.getClient(_selectedModel.value)
                AppLogger.d(TAG, "Requesting access token for model: ${_selectedModel.value.displayName}")
                
                when (_selectedModel.value.type) {
                    AiModelType.GIGACHAT -> {
                        val gigaChatClient = client as GigaChatApiClient
                        val token = gigaChatClient.getAccessToken()
                        setAccessToken(token)
                        AppLogger.i(TAG, "GigaChat access token obtained successfully")
                    }
                    AiModelType.HUGGINGFACE -> {
                        // HuggingFace: используем сохраненный токен или получаем встроенный
                        if (_huggingFaceToken.value.isNotBlank()) {
                            setHuggingFaceToken(_huggingFaceToken.value)
                            AppLogger.i(TAG, "HuggingFace token configured from saved value")
                        } else {
                            // Пытаемся получить встроенный токен
                            val token = client.getAccessToken("")
                            if (token.isNotBlank()) {
                                setHuggingFaceToken(token)
                                AppLogger.i(TAG, "HuggingFace token configured from built-in token")
                            } else {
                                throw Exception("HuggingFace API token is required. Please set it in settings.")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                val errorMsg = e.message ?: "Failed to get access token"
                val fullError = if (e.cause != null && e.cause?.message != null) {
                    "$errorMsg\n\nCause: ${e.cause?.message}"
                } else {
                    errorMsg
                }
                AppLogger.e(TAG, "Error getting access token: $fullError", e)
                _errorMessage.value = fullError
            } finally {
                _isLoadingToken.value = false
            }
        }
    }
    
    fun sendMessage(text: String) {
        if (text.isBlank() || _isLoading.value) {
            AppLogger.w(TAG, "Cannot send message: text is blank or already loading")
            return
        }
        
        AppLogger.d(TAG, "Sending message: ${text.take(50)}...")
        
        val userMessage = Message(
            id = generateId(),
            content = text.trim(),
            role = if (_useSystemRole.value) MessageRole.SYSTEM else MessageRole.USER
        )
        
        _session.value.messages.add(userMessage)
        // Сохраняем историю после добавления сообщения пользователя
        saveHistory()
        
        _isLoading.value = true
        _errorMessage.value = null
        
        viewModelScope.launch {
            try {
                val client = apiClientManager.getClient(_selectedModel.value)
                
                // Если токен пустой, пытаемся получить его автоматически
                if (_selectedModel.value.type == AiModelType.GIGACHAT && _accessToken.value.isBlank()) {
                    AppLogger.d(TAG, "Access token is blank, attempting to get it automatically")
                    try {
                        val gigaChatClient = client as GigaChatApiClient
                        val token = gigaChatClient.getAccessToken()
                        setAccessToken(token)
                        AppLogger.i(TAG, "Access token obtained automatically before sending message")
                    } catch (e: Exception) {
                        val baseMsg = e.message ?: "Unknown error"
                        val errorMsg = "Failed to get access token: $baseMsg"
                        val fullError = if (e.cause != null && e.cause?.message != null) {
                            "$errorMsg\n\nCause: ${e.cause?.message}"
                        } else {
                            errorMsg
                        }
                        AppLogger.e(TAG, fullError, e)
                        _errorMessage.value = fullError
                        _session.value.messages.removeLast() // Удаляем сообщение пользователя при ошибке
                        _isLoading.value = false
                        return@launch
                    }
                } else if (_selectedModel.value.type == AiModelType.HUGGINGFACE) {
                    // Проверяем токен: используем сохраненный или пытаемся получить встроенный
                    if (_huggingFaceToken.value.isBlank()) {
                        try {
                            val huggingFaceClient = client as? HuggingFaceApiClient
                            val token = huggingFaceClient?.getAccessToken() ?: ""
                            if (token.isNotBlank()) {
                                setHuggingFaceToken(token)
                            } else {
                                val errorMsg = "HuggingFace API token is required. Please set it in settings."
                                AppLogger.e(TAG, errorMsg)
                                _errorMessage.value = errorMsg
                                _session.value.messages.removeLast()
                                _isLoading.value = false
                                return@launch
                            }
                        } catch (e: Exception) {
                            val errorMsg = "HuggingFace API token is required. Please set it in settings."
                            AppLogger.e(TAG, errorMsg, e)
                            _errorMessage.value = errorMsg
                            _session.value.messages.removeLast()
                            _isLoading.value = false
                            return@launch
                        }
                    }
                }
                
                // Определяем maxTokens только для HuggingFace моделей
                val maxTokens = if (_selectedModel.value.type == AiModelType.HUGGINGFACE) {
                    _maxTokens.value
                } else {
                    null
                }
                
                // Проверяем, нужно ли вызвать MCP инструменты перед отправкой к AI
                val mcpToolResults = checkAndCallMcpTools(text)
                
                // Получаем информацию о доступных MCP инструментах для системного промпта
                val availableMcpTools = getAvailableMcpToolsInfo()
                
                // Формируем список сообщений для отправки:
                // 1. Загруженная история (если есть) - не показывается в UI
                // 2. Если есть summary, добавляем его как системное сообщение
                // 3. Информация о доступных MCP инструментах (системный промпт)
                // 4. Результаты MCP инструментов (если были вызваны)
                // 5. Исключаем сообщения пользователя, которые уже сжаты в summary
                // 6. Добавляем остальные сообщения (ответы AI и новые сообщения пользователя)
                val messagesToSend = buildList {
                    // Добавляем загруженную историю в начало (если есть)
                    _loadedHistory?.let { loaded ->
                        addAll(loaded)
                    }
                    
                    // Если есть сжатое резюме, добавляем его как системное сообщение
                    _compressedSummary?.let { compressed ->
                        add(Message(
                            id = generateId(),
                            content = compressed.summary,
                            role = MessageRole.SYSTEM
                        ))
                    }
                    
                    // Добавляем полный system prompt с инструкциями по использованию MCP инструментов
                    val systemPrompt = generateSystemPromptWithMcpTools()
                    if (systemPrompt.isNotEmpty()) {
                        add(Message(
                            id = generateId(),
                            content = systemPrompt,
                            role = MessageRole.SYSTEM
                        ))
                        AppLogger.d(TAG, "Added comprehensive system prompt with MCP tools instructions")
                    }
                    
                    // Добавляем результаты MCP инструментов как системные сообщения
                    if (mcpToolResults.isNotEmpty()) {
                        val toolContext = buildString {
                            append("Доступна следующая информация из внешних инструментов:\n\n")
                            mcpToolResults.forEach { (toolName, result) ->
                                append("[$toolName]: $result\n\n")
                            }
                            append("Используй эту информацию для ответа на вопрос пользователя.")
                        }
                        add(Message(
                            id = generateId(),
                            content = toolContext,
                            role = MessageRole.SYSTEM
                        ))
                        AppLogger.d(TAG, "Added MCP tool results to context: ${mcpToolResults.keys.joinToString()}")
                    }
                    
                    // Добавляем сообщения из сессии, исключая сжатые сообщения (и пользователя, и AI)
                    _session.value.messages.forEach { message ->
                        // Включаем только те сообщения, которые не сжаты
                        if (message.id !in _compressedMessageIds) {
                            add(message)
                        }
                    }
                }
                
                // Подсчитываем общее количество сообщений в запросе
                val totalMessagesCount = messagesToSend.size
                
                // Логируем состав запроса для отладки
                val loadedHistoryCount = _loadedHistory?.size ?: 0
                val summaryCount = if (_compressedSummary != null) 1 else 0
                val sessionMessagesCount = _session.value.messages.count { it.id !in _compressedMessageIds }
                AppLogger.d(TAG, "MessagesToSend composition for ${_selectedModel.value.displayName}: loaded history=$loadedHistoryCount (user msgs: $_loadedHistoryUserMessagesCount), summary=$summaryCount, session=$sessionMessagesCount, total=$totalMessagesCount")
                
                // Детальное логирование для отладки
                if (_loadedHistory != null) {
                    AppLogger.d(TAG, "Loaded history content: ${_loadedHistory!!.map { "${it.role}: ${it.content.take(50)}..." }.joinToString(", ")}")
                }
                if (_compressedSummary != null) {
                    AppLogger.d(TAG, "Compressed summary: ${_compressedSummary!!.summary.take(100)}...")
                }
                
                // Подсчитываем количество сообщений от пользователя (USER) для логирования:
                // загруженная история + сжатые сообщения пользователя из summary + текущие сообщения пользователя (не сжатые)
                val currentUserMessagesCount = _session.value.messages.count { 
                    it.role == MessageRole.USER && it.id !in _compressedMessageIds 
                }
                val userMessagesCount = _loadedHistoryUserMessagesCount + 
                    (_compressedSummary?.compressedUserMessagesCount ?: 0) + 
                    currentUserMessagesCount
                
                // Подсчитываем общую длину для логирования
                val totalLength = messagesToSend.sumOf { it.content.length }
                val summaryLength = _compressedSummary?.summary?.length ?: 0
                val regularLength = _session.value.messages.sumOf { it.content.length }
                
                AppLogger.d(TAG, "Calling AI API (${_selectedModel.value.displayName}) with $totalMessagesCount messages (${_compressedSummary?.let { "1 summary (${summaryLength} chars, ${it.compressedUserMessagesCount} user msgs) + " } ?: ""}${_session.value.messages.size} regular (${regularLength} chars)), total user messages: $userMessagesCount (compressed: ${_compressedSummary?.compressedUserMessagesCount ?: 0}, current: $currentUserMessagesCount), total: ${totalLength} chars, temperature: ${_temperature.value}, maxTokens: $maxTokens")
                
                // Засекаем время начала запроса
                val startTime = currentTimeMillis()
                val response = client.sendMessage(messagesToSend, _temperature.value, maxTokens)
                val endTime = currentTimeMillis()
                val responseTime = endTime - startTime
                
                // После успешной отправки удаляем загруженную историю (она уже использована)
                if (_loadedHistory != null) {
                    _loadedHistory = null
                    _loadedHistoryUserMessagesCount = 0
                    AppLogger.d(TAG, "Loaded history used and cleared after successful request")
                }
                
                AppLogger.d(TAG, "Received response from AI, length: ${response.length}, response time: ${responseTime}ms")
                AppLogger.i(TAG, "AI Response: $response")
                
                // Получаем информацию о токенах для HuggingFace моделей
                var promptTokens: Int? = null
                var completionTokens: Int? = null
                var totalTokens: Int? = null
                
                if (_selectedModel.value.type == AiModelType.HUGGINGFACE) {
                    val huggingFaceClient = client as? HuggingFaceApiClient
                    huggingFaceClient?.getLastUsage()?.let { usage ->
                        promptTokens = usage.prompt_tokens
                        completionTokens = usage.completion_tokens
                        totalTokens = usage.total_tokens
                        AppLogger.d(TAG, "Token usage saved: prompt=$promptTokens, completion=$completionTokens, total=$totalTokens")
                    }
                }
                
                val assistantMessage = Message(
                    id = generateId(),
                    content = response,
                    role = MessageRole.ASSISTANT,
                    responseTimeMs = responseTime,
                    promptTokens = promptTokens,
                    completionTokens = completionTokens,
                    totalTokens = totalTokens,
                    sentMessagesCount = totalMessagesCount
                )
                _session.value.messages.add(assistantMessage)
                AppLogger.i(TAG, "Message successfully processed and added to session (response time: ${responseTime}ms)")
                
                // Сохраняем историю после добавления сообщения
                saveHistory()
                
                // Проверяем и сжимаем историю, если необходимо
                checkAndCompressHistory()
            } catch (e: Exception) {
                val errorMsg = e.message ?: "Failed to get response"
                val fullError = if (e.cause != null && e.cause?.message != null) {
                    "$errorMsg\n\nCause: ${e.cause?.message}"
                } else {
                    errorMsg
                }
                AppLogger.e(TAG, "Error sending message: $fullError", e)
                _errorMessage.value = fullError
                // Удаляем сообщение пользователя при ошибке (опционально)
                // _session.value.messages.removeLast()
            } finally {
                _isLoading.value = false
            }
        }
    }
    
    fun clearChat() {
        // Сохраняем текущую историю перед очисткой
        saveHistory()
        
        _session.value = ChatSession()
        _compressedSummary = null
        _compressedMessageIds = emptySet()
        _loadedHistory = null // Очищаем загруженную историю
        _loadedHistoryUserMessagesCount = 0
        _errorMessage.value = null
        
        // Проверяем, есть ли сохраненная история для текущей модели
        checkForSavedHistory()
    }
    
    /**
     * Проверяет наличие сохраненной истории для текущей модели и предлагает загрузить её
     */
    private fun checkForSavedHistory() {
        viewModelScope.launch {
            val modelName = _selectedModel.value.name
            val hasHistory = hasChatHistory(modelName)
            if (hasHistory && _session.value.messages.isEmpty()) {
                _pendingHistoryModel = modelName
                _showLoadHistoryDialog.value = true
            }
        }
    }
    
    /**
     * Загружает сохраненную историю для текущей модели (не показывается в UI, используется только в запросах)
     * История загружается как summary и используется как системное сообщение
     */
    fun loadHistory() {
        viewModelScope.launch {
            val modelName = _pendingHistoryModel ?: _selectedModel.value.name
            try {
                val savedHistory = loadChatHistory(modelName)
                if (savedHistory != null && savedHistory.summary.isNotBlank()) {
                    // Создаем системное сообщение из summary для использования в запросах
                    val summaryMessage = Message(
                        id = generateId(),
                        content = savedHistory.summary,
                        role = MessageRole.SYSTEM
                    )
                    // Сохраняем как загруженную историю (не показывается в UI)
                    _loadedHistory = listOf(summaryMessage)
                    _loadedHistoryUserMessagesCount = savedHistory.userMessagesCount
                    AppLogger.i(TAG, "Loaded history summary for model: $modelName, user messages: ${savedHistory.userMessagesCount}, summary length: ${savedHistory.summary.length} (not shown in UI)")
                    AppLogger.d(TAG, "Loaded history will be added to next request. Summary preview: ${savedHistory.summary.take(100)}...")
                    
                    // Удаляем сохраненную историю после загрузки
                    deleteChatHistory(modelName)
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to load history: ${e.message}", e)
                _errorMessage.value = "Не удалось загрузить историю: ${e.message}"
            } finally {
                _showLoadHistoryDialog.value = false
                _pendingHistoryModel = null
            }
        }
    }
    
    /**
     * Отказывается от загрузки истории и удаляет сохраненную историю
     */
    fun dismissLoadHistory() {
        viewModelScope.launch {
            val modelName = _pendingHistoryModel ?: _selectedModel.value.name
            deleteChatHistory(modelName)
            AppLogger.d(TAG, "User dismissed history load for model: $modelName")
            _showLoadHistoryDialog.value = false
            _pendingHistoryModel = null
        }
    }
    
    /**
     * Сохраняет текущую историю для текущей модели в виде summary
     */
    private fun saveHistory() {
        viewModelScope.launch {
            if (_session.value.messages.isNotEmpty()) {
                try {
                    val modelName = _selectedModel.value.name
                    val client = apiClientManager.getClient(_selectedModel.value)
                    
                    // Формируем текст всех сообщений для создания summary
                    val conversationText = _session.value.messages.joinToString("\n\n") { msg ->
                        val role = when (msg.role) {
                            MessageRole.USER -> "Пользователь"
                            MessageRole.ASSISTANT -> "Ассистент"
                            MessageRole.SYSTEM -> "Система"
                        }
                        "$role: ${msg.content}"
                    }
                    
                    val totalLength = _session.value.messages.sumOf { it.content.length }
                    val userMessagesCount = _session.value.messages.count { it.role == MessageRole.USER }
                    val maxSummaryTokens = maxOf(100, (totalLength * 0.3).toInt() / 3)
                    
                    val summaryPrompt = """
                        Создай ОЧЕНЬ краткое резюме следующего диалога, сохраняя ТОЛЬКО ключевые моменты и важные решения.
                        Резюме должно быть на русском языке и быть максимально сжатым (максимум $maxSummaryTokens токенов).
                        
                        Диалог:
                        $conversationText
                        
                        Краткое резюме (максимум $maxSummaryTokens токенов):
                    """.trimIndent()
                    
                    val summaryRequestMessages = listOf(
                        Message(
                            id = generateId(),
                            content = summaryPrompt,
                            role = MessageRole.USER
                        )
                    )
                    
                    AppLogger.d(TAG, "Creating summary for history save: ${_session.value.messages.size} messages, user messages: $userMessagesCount")
                    val summary = client.sendMessage(summaryRequestMessages, temperature = 0.2f, maxTokens = maxSummaryTokens)
                    AppLogger.d(TAG, "Summary created for history save, length: ${summary.length}")
                    
                    // Сохраняем summary вместо полного списка сообщений
                    val success = saveChatHistory(modelName, summary, userMessagesCount)
                    if (success) {
                        AppLogger.d(TAG, "History saved as summary for model: $modelName, original messages: ${_session.value.messages.size}, user messages: $userMessagesCount")
                    }
                } catch (e: Exception) {
                    AppLogger.e(TAG, "Failed to create and save history summary: ${e.message}", e)
                    // В случае ошибки не прерываем работу
                }
            }
        }
    }
    
    fun dismissError() {
        _errorMessage.value = null
    }
    
    /**
     * Переключает режим диалога между NORMAL и SUMMARY.
     * При переключении очищает историю сообщений.
     */
    fun toggleDialogueMode() {
        val newMode = when (_dialogueMode.value) {
            DialogueMode.NORMAL -> DialogueMode.SUMMARY
            DialogueMode.SUMMARY -> DialogueMode.NORMAL
        }
        AppLogger.d(TAG, "Switching dialogue mode from ${_dialogueMode.value} to $newMode")
        _dialogueMode.value = newMode
        // Очищаем историю и summary при переключении режима
        _compressedSummary = null
        _compressedMessageIds = emptySet()
        _loadedHistory = null // Очищаем загруженную историю
        _loadedHistoryUserMessagesCount = 0
        clearChat()
    }
    
    /**
     * Сжимает историю диалога, создавая summary последних N сообщений (всех типов).
     * Сообщения остаются в UI, но исключаются из запросов, заменяясь на summary.
     */
    private suspend fun compressHistory() {
        val messages = _session.value.messages
        
        if (messages.size < COMPRESSION_THRESHOLD) {
            AppLogger.d(TAG, "Not enough messages to compress: ${messages.size} < $COMPRESSION_THRESHOLD")
            return
        }
        
        AppLogger.d(TAG, "Starting history compression: ${messages.size} total messages")
        
        try {
            val client = apiClientManager.getClient(_selectedModel.value)
            
            // Берем последние COMPRESSION_THRESHOLD сообщений (всех типов)
            val messagesToCompress = messages.takeLast(COMPRESSION_THRESHOLD)
            val compressMessageIds = messagesToCompress.map { it.id }.toSet()
            
            if (messagesToCompress.isEmpty()) {
                AppLogger.d(TAG, "No messages to compress")
                return
            }
            
            // Подсчитываем длину новых сообщений для сжатия
            val newMessagesLength = messagesToCompress.sumOf { it.content.length }
            val previousSummaryLength = _compressedSummary?.summary?.length ?: 0
            
            // Подсчитываем количество сообщений пользователя в сжатых сообщениях
            val newUserMessagesCount = messagesToCompress.count { it.role == MessageRole.USER }
            
            // Ограничиваем длину summary: максимум 30% от длины новых сообщений, но не менее 100 символов
            val maxNewSummaryLength = maxOf(100, (newMessagesLength * 0.3).toInt())
            val maxSummaryTokens = maxOf(50, maxNewSummaryLength / 3)
            
            // Формируем текст для summary (включая и сообщения пользователя, и ответы AI)
            val conversationText = messagesToCompress.joinToString("\n\n") { msg ->
                val role = when (msg.role) {
                    MessageRole.USER -> "Пользователь"
                    MessageRole.ASSISTANT -> "Ассистент"
                    MessageRole.SYSTEM -> "Система"
                }
                "$role: ${msg.content}"
            }
            
            val summaryPrompt = if (_compressedSummary != null) {
                // Если есть предыдущий summary, создаем новый summary только из новых сообщений,
                // а затем объединяем его с предыдущим summary
                """
                    Создай ОЧЕНЬ краткое резюме следующей части диалога (максимум $maxSummaryTokens токенов).
                    Резюме должно быть на русском языке и содержать ТОЛЬКО ключевые моменты и важные решения.
                    
                    Новая часть диалога:
                    $conversationText
                    
                    Краткое резюме новой части (максимум $maxSummaryTokens токенов):
                """.trimIndent()
            } else {
                """
                    Создай ОЧЕНЬ краткое резюме следующего диалога, сохраняя ТОЛЬКО ключевые моменты и важные решения.
                    Резюме должно быть на русском языке и быть максимально сжатым (максимум $maxSummaryTokens токенов).
                    
                    Диалог:
                    $conversationText
                    
                    Краткое резюме (максимум $maxSummaryTokens токенов):
                """.trimIndent()
            }
            
            // Создаем список сообщений для запроса summary
            val summaryRequestMessages = listOf(
                Message(
                    id = generateId(),
                    content = summaryPrompt,
                    role = MessageRole.USER
                )
            )
            
            AppLogger.d(TAG, "Requesting summary for ${messagesToCompress.size} messages (previous summary: ${_compressedSummary != null}, new messages length: $newMessagesLength, new user messages: $newUserMessagesCount, max tokens: $maxSummaryTokens)")
            val newSummary = client.sendMessage(summaryRequestMessages, temperature = 0.2f, maxTokens = maxSummaryTokens)
            AppLogger.d(TAG, "New summary received, length: ${newSummary.length} (new messages: $newMessagesLength, compression ratio: ${if (newMessagesLength > 0) (newSummary.length.toDouble() / newMessagesLength * 100).toInt() else 0}%)")
            
            // Объединяем новый summary с предыдущим (если есть) в компактном виде
            val (finalSummary, finalUserMessagesCount) = if (_compressedSummary != null) {
                // Если есть предыдущий summary, объединяем их компактно
                val combinedLength = _compressedSummary!!.summary.length + newSummary.length
                val maxCombinedTokens = maxOf(100, (combinedLength * 0.4).toInt() / 3) // Ограничиваем объединенный summary
                
                val combinePrompt = """
                    Объедини два резюме в одно КРАТКОЕ резюме (максимум $maxCombinedTokens токенов).
                    Удали избыточную информацию и повторы. Сохрани только ключевые моменты.
                    
                    Предыдущее резюме:
                    ${_compressedSummary!!.summary}
                    
                    Новое резюме:
                    $newSummary
                    
                    Объединенное краткое резюме (максимум $maxCombinedTokens токенов):
                """.trimIndent()
                
                val combineMessages = listOf(
                    Message(
                        id = generateId(),
                        content = combinePrompt,
                        role = MessageRole.USER
                    )
                )
                
                val combined = client.sendMessage(combineMessages, temperature = 0.2f, maxTokens = maxCombinedTokens)
                val totalUserMessagesCount = _compressedSummary!!.compressedUserMessagesCount + newUserMessagesCount
                AppLogger.d(TAG, "Combined summary length: ${combined.length} (previous: ${_compressedSummary!!.summary.length}, new: ${newSummary.length}, total before: $combinedLength), total user messages: $totalUserMessagesCount")
                Pair(combined, totalUserMessagesCount)
            } else {
                Pair(newSummary, newUserMessagesCount)
            }
            
            // Сохраняем итоговый summary отдельно (не добавляем в messages и не удаляем из UI)
            _compressedSummary = CompressedSummary(
                summary = finalSummary,
                compressedUserMessagesCount = finalUserMessagesCount
            )
            
            // Сохраняем ID всех сжатых сообщений (пользователя и AI) для исключения их из запросов
            // Если есть предыдущие сжатые сообщения, объединяем их с новыми
            _compressedMessageIds = _compressedMessageIds + compressMessageIds
            
            AppLogger.d(TAG, "Compressed ${messagesToCompress.size} messages (${newUserMessagesCount} user + ${messagesToCompress.size - newUserMessagesCount} AI), total compressed IDs: ${_compressedMessageIds.size}")
            
            // НЕ удаляем сообщения из UI - они остаются видимыми
            AppLogger.i(TAG, "History compressed: ${messagesToCompress.size} user messages compressed into summary (${finalUserMessagesCount} total), UI messages unchanged: ${messages.size} messages")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to compress history: ${e.message}", e)
            // В случае ошибки не сжимаем историю, продолжаем работу
            _errorMessage.value = "Не удалось сжать историю: ${e.message}"
        }
    }
    
    /**
     * Проверяет, нужно ли сжимать историю, и выполняет сжатие если необходимо.
     * Сжатие происходит когда накопилось COMPRESSION_THRESHOLD сообщений (всех типов).
     */
    private suspend fun checkAndCompressHistory() {
        if (_dialogueMode.value == DialogueMode.SUMMARY) {
            // Считаем все сообщения (пользователя и AI)
            val totalMessagesCount = _session.value.messages.size
            
            // Сжимаем, если количество сообщений кратно порогу
            if (totalMessagesCount > 0 && totalMessagesCount % COMPRESSION_THRESHOLD == 0) {
                compressHistory()
            }
        }
    }
    
    /**
     * Подключает MCP сервер по URL
     */
    fun connectMcpServer(name: String, url: String) {
        mcpClientManager?.let { manager ->
            viewModelScope.launch {
                try {
                    val connected = manager.connectServer(name, url)
                    if (connected) {
                        val updatedServers = _mcpServers.value.toMutableList()
                        val existingIndex = updatedServers.indexOfFirst { it.name == name }
                        if (existingIndex >= 0) {
                            updatedServers[existingIndex] = McpServerConfig(name, url, true)
                        } else {
                            updatedServers.add(McpServerConfig(name, url, true))
                        }
                        _mcpServers.value = updatedServers
                        AppLogger.i(TAG, "MCP server connected: $name")
                    } else {
                        _errorMessage.value = "Не удалось подключиться к MCP серверу: $name"
                    }
                } catch (e: Exception) {
                    AppLogger.e(TAG, "Failed to connect MCP server: ${e.message}", e)
                    _errorMessage.value = "Ошибка подключения к MCP серверу: ${e.message}"
                }
            }
        }
    }
    
    /**
     * Отключает MCP сервер
     */
    fun disconnectMcpServer(name: String) {
        mcpClientManager?.disconnectServer(name)
        val updatedServers = _mcpServers.value.map { 
            if (it.name == name) it.copy(connected = false) else it
        }
        _mcpServers.value = updatedServers
        AppLogger.d(TAG, "MCP server disconnected: $name")
    }
    
    /**
     * Добавляет MCP сервер в список (без подключения)
     */
    fun addMcpServer(name: String, url: String) {
        val updatedServers = _mcpServers.value.toMutableList()
        if (updatedServers.any { it.name == name }) {
            _errorMessage.value = "Сервер с именем '$name' уже существует"
            return
        }
        updatedServers.add(McpServerConfig(name, url, false))
        _mcpServers.value = updatedServers
        AppLogger.d(TAG, "MCP server added: $name")
    }
    
    /**
     * Удаляет MCP сервер из списка
     */
    fun removeMcpServer(name: String) {
        disconnectMcpServer(name)
        val updatedServers = _mcpServers.value.filter { it.name != name }
        _mcpServers.value = updatedServers
        AppLogger.d(TAG, "MCP server removed: $name")
    }
    
    /**
     * Подключает все MCP серверы из списка
     */
    fun connectAllMcpServers() {
        _mcpServers.value.forEach { server ->
            if (!server.connected) {
                connectMcpServer(server.name, server.url)
            }
        }
    }
    
    /**
     * Подключает несколько MCP серверов из списка URL
     * Формат списка: ["name1:url1", "name2:url2"] или ["url1", "url2"] (имена будут сгенерированы)
     */
    fun connectMcpServersFromList(urlList: List<String>) {
        viewModelScope.launch {
            mcpClientManager?.let { manager ->
                try {
                    val results = manager.connectServersFromList(urlList)
                    
                    // Обновляем состояние серверов
                    val updatedServers = _mcpServers.value.toMutableList()
                    results.forEach { (name, connected) ->
                        val existingIndex = updatedServers.indexOfFirst { it.name == name }
                        val url = manager.getClient(name)?.serverUrl ?: urlList.find { 
                            it.contains(name) || it.endsWith(name) 
                        } ?: ""
                        
                        if (existingIndex >= 0) {
                            updatedServers[existingIndex] = McpServerConfig(name, url, connected)
                        } else if (url.isNotBlank()) {
                            updatedServers.add(McpServerConfig(name, url, connected))
                        }
                    }
                    _mcpServers.value = updatedServers
                    
                    val successCount = results.values.count { it }
                    AppLogger.i(TAG, "Connected $successCount/${results.size} MCP servers from list")
                    
                    if (successCount < results.size) {
                        val failed = results.filter { !it.value }.keys.joinToString(", ")
                        _errorMessage.value = "Не удалось подключить некоторые серверы: $failed"
                    }
                } catch (e: Exception) {
                    AppLogger.e(TAG, "Failed to connect MCP servers from list: ${e.message}", e)
                    _errorMessage.value = "Ошибка подключения серверов: ${e.message}"
                }
            }
        }
    }
    
    fun showMcpServerDialog() {
        _showMcpServerDialog.value = true
    }
    
    fun hideMcpServerDialog() {
        _showMcpServerDialog.value = false
    }
    
    /**
     * Импортирует популярные MCP серверы с mcpservers.org
     * Список можно найти на: https://mcpservers.org/remote-mcp-servers
     * 
     * Примечание: URL серверов должны указывать на MCP endpoint.
     * Обычно это базовый домен (например, https://notion.mcpservers.org),
     * клиент автоматически добавит /mcp или /sse если нужно.
     */
    fun importPopularMcpServers() {
        // Популярные remote MCP серверы с mcpservers.org
        // Формат: "Имя:URL" где URL - базовый адрес сервера
        // Клиент автоматически определит правильный endpoint (/mcp или /sse)
        val popularServers = listOf(
            "Notion:https://notion.mcpservers.org",
            "Sentry:https://sentry.mcpservers.org",
            "Linear:https://linear.mcpservers.org",
            "Figma:https://figma.mcpservers.org",
            "DeepWiki:https://deepwiki.mcpservers.org",
            "Intercom:https://intercom.mcpservers.org",
            "Neon:https://neon.mcpservers.org",
            "Supabase:https://supabase.mcpservers.org",
            "PayPal:https://paypal.mcpservers.org",
            "Square:https://square.mcpservers.org",
            "CoinGecko:https://coingecko.mcpservers.org",
            "Ahrefs:https://ahrefs.mcpservers.org",
            "Asana:https://asana.mcpservers.org",
            "Atlassian:https://atlassian.mcpservers.org",
            "Wix:https://wix.mcpservers.org",
            "Webflow:https://webflow.mcpservers.org",
            "Globalping:https://globalping.mcpservers.org",
            "Semgrep:https://semgrep.mcpservers.org",
            "Fetch:https://fetch.mcpservers.org",
            "Sequential Thinking:https://sequential-thinking.mcpservers.org",
            "EdgeOne Pages:https://edgeone-pages.mcpservers.org"
        )
        
        AppLogger.i(TAG, "Importing ${popularServers.size} popular MCP servers from mcpservers.org")
        connectMcpServersFromList(popularServers)
    }
    
    /**
     * Импортирует серверы из текстового списка
     * Формат: каждая строка может быть "имя:URL" или просто "URL"
     */
    fun importMcpServersFromText(text: String) {
        val servers = text.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("#") } // Игнорируем пустые строки и комментарии
        
        if (servers.isNotEmpty()) {
            connectMcpServersFromList(servers)
        }
    }
    
    /**
     * Вызывает MCP инструмент через подключенный MCP сервер
     * Используется агентом для получения данных из внешних источников
     */
    suspend fun callMcpTool(serverName: String, toolName: String, arguments: kotlinx.serialization.json.JsonObject?): String? {
        return try {
            val client = mcpClientManager?.getClient(serverName)
            if (client == null) {
                AppLogger.w(TAG, "MCP client '$serverName' not found")
                return null
            }
            
            AppLogger.d(TAG, "Calling MCP tool: $toolName on server: $serverName")
            val result = client.callTool(toolName, arguments)
            
            if (result != null && !result.isError) {
                val content = result.content.firstOrNull()?.text ?: "No result"
                AppLogger.d(TAG, "MCP tool result: $content")
                content
            } else {
                val errorMsg = result?.content?.firstOrNull()?.text ?: "Unknown error"
                AppLogger.e(TAG, "MCP tool error: $errorMsg")
                "Ошибка при вызове инструмента: $errorMsg"
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to call MCP tool: ${e.message}", e)
            "Ошибка: ${e.message}"
        }
    }
    
    /**
     * Получает список доступных инструментов с подключенного MCP сервера
     */
    suspend fun getMcpTools(serverName: String): List<org.example.demo.chat.McpTool> {
        return try {
            val client = mcpClientManager?.getClient(serverName)
            client?.listTools() ?: emptyList()
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to get MCP tools: ${e.message}", e)
            emptyList()
        }
    }
    
    /**
     * Получает информацию о всех доступных MCP инструментах со всех подключенных серверов
     * Возвращает карту: имя сервера -> список инструментов
     */
    private suspend fun getAvailableMcpToolsInfo(): Map<String, List<org.example.demo.chat.McpTool>> {
        val toolsInfo = mutableMapOf<String, List<org.example.demo.chat.McpTool>>()
        
        if (mcpClientManager == null) {
            return toolsInfo
        }
        
        val servers = mcpClientManager.getConnectedServers()
        for (serverName in servers) {
            try {
                val tools = getMcpTools(serverName)
                if (tools.isNotEmpty()) {
                    toolsInfo[serverName] = tools
                }
            } catch (e: Exception) {
                AppLogger.w(TAG, "Failed to get tools from server $serverName: ${e.message}")
            }
        }
        
        return toolsInfo
    }
    
    /**
     * Генерирует полный system prompt для GigaChat с инструкциями по использованию MCP инструментов
     */
    private suspend fun generateSystemPromptWithMcpTools(): String {
        val availableTools = getAvailableMcpToolsInfo()
        
        if (availableTools.isEmpty()) {
            return """Ты - полезный AI ассистент. Помогай пользователю с вопросами и задачами.
            
Если тебе нужна дополнительная информация из внешних источников, сообщи пользователю об этом, и система автоматически получит необходимые данные."""
        }
        
        val prompt = buildString {
            append("""Ты - полезный AI ассистент с доступом к MCP (Model Context Protocol) инструментам для получения контекста и выполнения действий.

## Доступные MCP инструменты:

""")
            
            // Описываем каждый сервер и его инструменты
            availableTools.forEach { (serverName, tools) ->
                append("### Сервер: $serverName\n\n")
                tools.forEach { tool ->
                    append("**${tool.name}**")
                    if (tool.description != null && tool.description.isNotBlank()) {
                        append(": ${tool.description}")
                    }
                    append("\n")
                    
                    // Добавляем информацию о параметрах, если есть
                    tool.inputSchema?.let { schema ->
                        schema.jsonObject["properties"]?.jsonObject?.let { properties ->
                            if (properties.isNotEmpty()) {
                                append("  Параметры:\n")
                                properties.forEach { (paramName, paramInfo) ->
                                    val paramObj = paramInfo.jsonObject
                                    val paramType = paramObj["type"]?.jsonPrimitive?.content ?: "string"
                                    val paramDesc = paramObj["description"]?.jsonPrimitive?.content
                                    val isRequired = schema.jsonObject["required"]?.jsonArray?.any { 
                                        it.jsonPrimitive.content == paramName 
                                    } ?: false
                                    
                                    append("    - $paramName ($paramType)")
                                    if (paramDesc != null) {
                                        append(": $paramDesc")
                                    }
                                    if (isRequired) {
                                        append(" [обязательный]")
                                    }
                                    append("\n")
                                }
                            }
                        }
                    }
                    append("\n")
                }
                append("\n")
            }
            
            append("""## Как использовать MCP инструменты:

### Важные принципы:

1. **Автоматический вызов**: Система автоматически вызывает инструменты при обнаружении соответствующих запросов пользователя. Тебе не нужно явно просить об этом.

2. **Запрос контекста**: Если тебе нужна информация, которую можно получить через MCP инструменты, просто упомяни об этом в своем ответе. Система автоматически определит необходимость и вызовет соответствующий инструмент.

3. **Использование результатов**: Когда система вызывает инструмент и получает результат, он будет добавлен в контекст как системное сообщение. Используй эту информацию для ответа пользователю.

### Примеры использования:

**Пример 1: Получение информации с сайта**
- Пользователь: "Расскажи о содержимом сайта https://example.com"
- Система автоматически вызовет инструмент `fetch` с URL
- Ты получишь содержимое сайта в контексте
- Используй эту информацию для ответа

**Пример 2: Сохранение информации**
- Пользователь: "Сохрани эту информацию: важные данные"
- Система автоматически вызовет инструмент `save_info`
- Ты получишь подтверждение сохранения
- Сообщи пользователю об успешном сохранении

**Пример 3: Создание напоминания**
- Пользователь: "Напомни мне завтра в 10:00 о встрече"
- Система автоматически вызовет инструмент `reminder`
- Ты получишь подтверждение создания напоминания
- Сообщи пользователю об успешном создании

**Пример 4: Комплексный запрос**
- Пользователь: "Получи информацию с сайта X и сохрани её"
- Система автоматически вызовет сначала `fetch`, затем `save_info`
- Ты получишь оба результата в контексте
- Используй оба результата для формирования ответа

### Когда запрашивать использование инструментов:

1. **fetch**: Когда пользователь просит получить информацию с веб-сайта, прочитать страницу, узнать содержимое сайта. Просто упомяни URL или попроси получить информацию - система автоматически вызовет инструмент.

2. **save_info**: Когда пользователь просит сохранить, запомнить информацию или добавить в базу знаний. Просто подтверди, что информация будет сохранена - система автоматически вызовет инструмент.

3. **reminder**: Когда пользователь просит создать напоминание, напомнить о чем-то. Просто подтверди создание напоминания - система автоматически вызовет инструмент.

4. **yandex_tracker**: Когда пользователь спрашивает о задачах в трекере, просит создать задачу или получить информацию о задачах. Система автоматически вызовет инструмент при обнаружении соответствующих запросов.

### Важно:

- **Не нужно явно просить систему вызвать инструмент** - система делает это автоматически
- **Используй результаты инструментов**, которые уже есть в контексте
- **Если нужна информация, которую можно получить через инструмент**, просто упомяни об этом естественным образом
- **Всегда используй полученную информацию** для формирования полного и полезного ответа

## Твоя задача:

1. Анализируй запросы пользователя
2. Используй информацию из результатов MCP инструментов (если она есть в контексте)
3. Формируй полные, полезные и точные ответы
4. Если нужна дополнительная информация, упомяни об этом - система автоматически получит её

Помни: MCP инструменты вызываются автоматически системой. Твоя задача - использовать полученные результаты для помощи пользователю.""")
        }
        
        return prompt.toString()
    }
    
    /**
     * Генерирует summary текста через GigaChat API
     */
    private suspend fun generateSummaryWithGigaChat(text: String, maxLength: Int = 300): String? {
        return try {
            val client = apiClientManager.getClient(AiModel.GIGACHAT)
            
            // Получаем токен, если нужно
            if (_accessToken.value.isBlank()) {
                try {
                    val gigaChatClient = client as? org.example.demo.chat.GigaChatApiClient
                    val token = gigaChatClient?.getAccessToken() ?: ""
                    if (token.isNotBlank()) {
                        setAccessToken(token)
                    } else {
                        AppLogger.w(TAG, "Could not get GigaChat access token for summary generation")
                        return null
                    }
                } catch (e: Exception) {
                    AppLogger.w(TAG, "Failed to get access token for summary: ${e.message}")
                    return null
                }
            }
            
            val prompt = """
                Создай краткую сводку следующего текста.
                Требования:
                - Максимальная длина: $maxLength символов
                - Сохрани основные идеи и ключевые моменты
                - Будь точным и информативным
                - Используй ясный и понятный язык
                - Выдели главные тезисы
                
                Текст:
                ${text.take(5000)}${if (text.length > 5000) "\n\n[Текст обрезан для обработки]" else ""}
            """.trimIndent()
            
            val summary = client.sendMessage(
                messages = listOf(
                    Message(
                        id = generateId(),
                        content = prompt,
                        role = MessageRole.USER
                    )
                ),
                temperature = 0.3f, // Низкая температура для более точных summary
                maxTokens = null
            )
            
            // Обрезаем до maxLength если нужно
            if (summary.length > maxLength) {
                summary.take(maxLength) + "..."
            } else {
                summary
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error generating summary with GigaChat: ${e.message}", e)
            null
        }
    }
    
    /**
     * Проверяет запрос пользователя и автоматически вызывает соответствующие MCP инструменты
     * Возвращает карту результатов: имя инструмента -> результат
     */
    private suspend fun checkAndCallMcpTools(userMessage: String): Map<String, String> {
        val results = mutableMapOf<String, String>()
        
        if (mcpClientManager == null) {
            AppLogger.d(TAG, "MCP client manager not available")
            return results
        }
        
        val lowerMessage = userMessage.lowercase()
        val serversToCheck = mcpClientManager?.getConnectedServers() ?: emptyList()
        
        if (serversToCheck.isEmpty()) {
            AppLogger.d(TAG, "No MCP servers connected")
            return results
        }
        
        AppLogger.d(TAG, "Checking MCP tools for message. Connected servers: ${serversToCheck.joinToString()}")
        
        // 1. Проверяем запросы на сохранение информации (save_info)
        val saveInfoKeywords = listOf(
            "сохрани", "сохранить", "запомни", "запомнить", "добавь в базу", 
            "сохрани информацию", "запомни это", "сохрани для будущего"
        )
        val hasSaveInfoRequest = saveInfoKeywords.any { keyword ->
            lowerMessage.contains(keyword, ignoreCase = true)
        }
        
        if (hasSaveInfoRequest) {
            AppLogger.d(TAG, "Detected save_info request, checking for MCP servers...")
            for (serverName in serversToCheck) {
                try {
                    val tools = getMcpTools(serverName)
                    val saveInfoTool = tools.find { it.name == "save_info" }
                    
                    if (saveInfoTool != null) {
                        AppLogger.d(TAG, "Found save_info tool on server: $serverName")
                        
                        // Извлекаем информацию для сохранения
                        val titleMatch = Regex("(?:название|title)[\\s:]+(.+)", RegexOption.IGNORE_CASE).find(userMessage)
                        val title = titleMatch?.groupValues?.get(1)?.trim() ?: "Сохраненная информация"
                        
                        // Пытаемся извлечь контент (весь текст после ключевых слов)
                        val contentStart = saveInfoKeywords.mapNotNull { keyword ->
                            val index = lowerMessage.indexOf(keyword)
                            if (index >= 0) index + keyword.length else null
                        }.minOrNull() ?: 0
                        val content = userMessage.substring(contentStart).trim()
                        
                        val result = callMcpTool(
                            serverName = serverName,
                            toolName = "save_info",
                            arguments = buildJsonObject {
                                put("action", "save")
                                put("title", title)
                                put("content", if (content.isNotEmpty()) content else userMessage)
                                putJsonArray("tags") {
                                    // Извлекаем теги из сообщения, если есть
                                    val tagMatch = Regex("(?:тег|tag)[\\s:]+([^\\s,]+)", RegexOption.IGNORE_CASE).find(userMessage)
                                    if (tagMatch != null) {
                                        add(tagMatch.groupValues[1])
                                    }
                                }
                            }
                        )
                        
                        if (result != null && !result.startsWith("Ошибка")) {
                            results["save_info"] = result
                            break
                        }
                    }
                } catch (e: Exception) {
                    AppLogger.e(TAG, "Error checking save_info tool on server $serverName: ${e.message}", e)
                }
            }
        }
        
        // 2. Проверяем запросы на напоминания (reminder)
        val reminderKeywords = listOf(
            "напомни", "напомнить", "напоминание", "reminder",
            "напомни мне", "создай напоминание", "добавь напоминание"
        )
        val hasReminderRequest = reminderKeywords.any { keyword ->
            lowerMessage.contains(keyword, ignoreCase = true)
        }
        
        if (hasReminderRequest) {
            AppLogger.d(TAG, "Detected reminder request, checking for MCP servers...")
            for (serverName in serversToCheck) {
                try {
                    val tools = getMcpTools(serverName)
                    val reminderTool = tools.find { it.name == "reminder" }
                    
                    if (reminderTool != null) {
                        AppLogger.d(TAG, "Found reminder tool on server: $serverName")
                        
                        // Извлекаем информацию о напоминании
                        val titleMatch = Regex("(?:напомни|напомнить|о)[\\s:]+(.+)", RegexOption.IGNORE_CASE).find(userMessage)
                        val title = titleMatch?.groupValues?.get(1)?.trim() ?: "Напоминание"
                        
                        // Пытаемся найти дату/время
                        val dateMatch = Regex("(?:завтра|послезавтра|через|в|в\\s+\\d+)", RegexOption.IGNORE_CASE).find(userMessage)
                        
                        val result = callMcpTool(
                            serverName = serverName,
                            toolName = "reminder",
                            arguments = buildJsonObject {
                                put("action", "create")
                                put("title", title)
                                put("description", userMessage)
                                if (dateMatch != null) {
                                    put("dueDate", dateMatch.value) // Упрощенная версия
                                }
                                put("priority", "medium")
                            }
                        )
                        
                        if (result != null && !result.startsWith("Ошибка")) {
                            results["reminder"] = result
                            break
                        }
                    }
                } catch (e: Exception) {
                    AppLogger.e(TAG, "Error checking reminder tool on server $serverName: ${e.message}", e)
                }
            }
        }
        
        // 3. Проверяем запросы на получение содержимого сайта (fetch)
        val fetchKeywords = listOf(
            "получи", "получить", "прочитай", "прочитать", "содержимое", "контент",
            "расскажи о сайте", "что на сайте", "информация с сайта", "fetch",
            "получи информацию", "прочитай сайт", "контент сайта", "содержимое сайта"
        )
        val urlPattern = Regex("https?://[^\\s]+", RegexOption.IGNORE_CASE)
        val hasUrl = urlPattern.containsMatchIn(userMessage)
        val hasFetchRequest = (fetchKeywords.any { keyword ->
            lowerMessage.contains(keyword, ignoreCase = true)
        } && hasUrl) || hasUrl // Если есть URL, даже без ключевых слов, пытаемся fetch
        
        if (hasFetchRequest) {
            AppLogger.d(TAG, "Detected fetch request, checking for MCP servers...")
            val urlMatch = urlPattern.find(userMessage)
            val url = urlMatch?.value
            
            if (url != null) {
                // Сначала пытаемся найти удаленный MCP сервер fetch (remote_fetch)
                // Затем проверяем другие серверы
                val preferredServers = listOf("remote_fetch") + serversToCheck.filter { it != "remote_fetch" }
                
                for (serverName in preferredServers) {
                    if (!serversToCheck.contains(serverName)) continue
                    
                    try {
                        val tools = getMcpTools(serverName)
                        val fetchTool = tools.find { it.name == "fetch" }
                        
                        if (fetchTool != null) {
                            AppLogger.d(TAG, "Found fetch tool on server: $serverName")
                            
                            val fetchResult = callMcpTool(
                                serverName = serverName,
                                toolName = "fetch",
                                arguments = buildJsonObject {
                                    put("url", url)
                                }
                            )
                            
                            if (fetchResult != null && !fetchResult.startsWith("Ошибка")) {
                                results["fetch"] = fetchResult
                                AppLogger.i(TAG, "Successfully fetched content from URL: $url")
                                
                                // Автоматически создаем summary через GigaChat и сохраняем
                                try {
                                    val summary = generateSummaryWithGigaChat(fetchResult)
                                    if (summary != null) {
                                        AppLogger.d(TAG, "Generated summary with GigaChat: ${summary.take(100)}...")
                                        
                                        // Сохраняем информацию с summary через локальный MCP сервер
                                        val localServerName = serversToCheck.find { it == "local" }
                                        if (localServerName != null) {
                                            val localTools = getMcpTools(localServerName)
                                            val saveInfoTool = localTools.find { it.name == "save_info" }
                                            
                                            if (saveInfoTool != null) {
                                                AppLogger.d(TAG, "Auto-saving fetched content with summary to local server")
                                                
                                                val saveResult = callMcpTool(
                                                    serverName = localServerName,
                                                    toolName = "save_info",
                                                    arguments = buildJsonObject {
                                                        put("action", "save")
                                                        put("title", "Информация с сайта: ${url.take(100)}")
                                                        put("content", fetchResult)
                                                        put("source", url)
                                                        put("summary", summary)
                                                        putJsonArray("tags") {
                                                            add("website")
                                                            add("fetch")
                                                            add("auto-saved")
                                                        }
                                                    }
                                                )
                                                
                                                if (saveResult != null && !saveResult.startsWith("Ошибка")) {
                                                    results["save_info"] = saveResult
                                                    AppLogger.i(TAG, "Successfully auto-saved fetched content with summary")
                                                } else {
                                                    AppLogger.w(TAG, "Failed to auto-save fetched content: $saveResult")
                                                }
                                            }
                                        }
                                    }
                                } catch (e: Exception) {
                                    AppLogger.e(TAG, "Error generating summary or saving: ${e.message}", e)
                                    // Продолжаем работу даже если summary/save не удалось
                                }
                                
                                break
                            } else {
                                AppLogger.w(TAG, "Failed to fetch content from $url via server $serverName: $fetchResult")
                            }
                        }
                    } catch (e: Exception) {
                        AppLogger.e(TAG, "Error checking fetch tool on server $serverName: ${e.message}", e)
                    }
                }
            }
        }
        
        // 4. Проверяем упоминания трекера/задач (существующая логика)
        val trackerKeywords = listOf(
            "трекер", "задач", "таск", "issue", "yandex tracker",
            "сколько задач", "открытые задачи", "список задач",
            "создать задачу", "добавить задачу", "новая задача"
        )
        
        val hasTrackerMention = trackerKeywords.any { keyword ->
            lowerMessage.contains(keyword, ignoreCase = true)
        }
        
        if (hasTrackerMention) {
            AppLogger.d(TAG, "Detected tracker-related query, checking for MCP servers...")
            
            // Пытаемся найти сервер с инструментом yandex_tracker
            var foundServer = false
            for (serverName in serversToCheck) {
                if (foundServer) break
                
                try {
                    val tools = getMcpTools(serverName)
                    val trackerTool = tools.find { it.name == "yandex_tracker" }
                    
                    if (trackerTool != null) {
                        AppLogger.d(TAG, "Found yandex_tracker tool on server: $serverName")
                        foundServer = true
                        
                        // Определяем, какое действие нужно вызвать
                        val taskIdMatch = Regex("test-\\d+", RegexOption.IGNORE_CASE).find(lowerMessage)
                        val taskId = taskIdMatch?.value?.uppercase()
                        
                        // Проверяем, нужно ли создать задачу
                        val shouldCreateTask = lowerMessage.contains("создать") || 
                                               lowerMessage.contains("добавить") || 
                                               lowerMessage.contains("новая задача")
                        
                        val action = when {
                            shouldCreateTask -> {
                                // Извлекаем название задачи из сообщения
                                // Ищем паттерны типа "создать задачу: название" или "добавить задачу название"
                                val createPatterns = listOf(
                                    Regex("создать задачу[\\s:]+(.+)", RegexOption.IGNORE_CASE),
                                    Regex("добавить задачу[\\s:]+(.+)", RegexOption.IGNORE_CASE),
                                    Regex("новая задача[\\s:]+(.+)", RegexOption.IGNORE_CASE),
                                    Regex("создай задачу[\\s:]+(.+)", RegexOption.IGNORE_CASE)
                                )
                                
                                var taskSummary: String? = null
                                var taskDescription: String? = null
                                
                                for (pattern in createPatterns) {
                                    val match = pattern.find(userMessage)
                                    if (match != null) {
                                        val extracted = match.groupValues[1].trim()
                                        // Разделяем на название и описание (если есть)
                                        val parts = extracted.split(Regex("[,;]|\\n"), limit = 2)
                                        taskSummary = parts[0].trim()
                                        if (parts.size > 1) {
                                            taskDescription = parts[1].trim()
                                        }
                                        break
                                    }
                                }
                                
                                // Если не нашли паттерн, используем весь текст после ключевых слов
                                if (taskSummary == null) {
                                    val keywordPattern = Regex("создать|добавить|новая", RegexOption.IGNORE_CASE)
                                    val match = keywordPattern.find(userMessage)
                                    if (match != null) {
                                        val afterKeywords = userMessage.substring(match.range.last + 1).trim()
                                        if (afterKeywords.isNotEmpty()) {
                                            val parts = afterKeywords.split(Regex("[,;]|\\n"), limit = 2)
                                            taskSummary = parts[0].trim()
                                            if (parts.size > 1) {
                                                taskDescription = parts[1].trim()
                                            }
                                        }
                                    }
                                }
                                
                                // Если все еще нет названия, используем часть сообщения
                                if (taskSummary.isNullOrBlank()) {
                                    // Берем первые 50 символов после ключевых слов как название
                                    val keywords = listOf("создать", "добавить", "новая")
                                    val startIndex = keywords.map { 
                                        userMessage.lowercase().indexOf(it) 
                                    }.filter { it >= 0 }.minOrNull() ?: 0
                                    
                                    val afterStartMatch = Regex("задач[ауеи]?[\\s:]+(.+)", RegexOption.IGNORE_CASE).find(userMessage.substring(startIndex))
                                    val afterStart = afterStartMatch?.groupValues?.get(1) ?: ""
                                    taskSummary = afterStart.take(100).trim().ifEmpty { "Новая задача" }
                                }
                                
                                // Вызываем инструмент для создания задачи
                                val result = callMcpTool(
                                    serverName = serverName,
                                    toolName = "yandex_tracker",
                                    arguments = buildJsonObject {
                                        put("action", "create_task")
                                        put("summary", taskSummary ?: "Новая задача")
                                        if (taskDescription != null) {
                                            put("description", taskDescription)
                                        }
                                        put("queue", "TEST")
                                    }
                                )
                                
                                if (result != null && !result.startsWith("Ошибка")) {
                                    results["yandex_tracker"] = result
                                }
                                null // Помечаем, что уже обработали
                            }
                            taskId != null -> {
                                // Получаем конкретную задачу
                                val result = callMcpTool(
                                    serverName = serverName,
                                    toolName = "yandex_tracker",
                                    arguments = buildJsonObject {
                                        put("action", "get_task")
                                        put("task_id", taskId)
                                        put("queue", "TEST")
                                    }
                                )
                                if (result != null && !result.startsWith("Ошибка")) {
                                    results["yandex_tracker"] = result
                                }
                                null // Помечаем, что уже обработали
                            }
                            lowerMessage.contains("сколько") || lowerMessage.contains("количество") -> "count_open_tasks"
                            lowerMessage.contains("список") || lowerMessage.contains("все") -> "get_open_tasks"
                            else -> "count_open_tasks" // По умолчанию подсчитываем
                        }
                        
                        if (action != null) {
                            val result = callMcpTool(
                                serverName = serverName,
                                toolName = "yandex_tracker",
                                arguments = buildJsonObject {
                                    put("action", action)
                                    put("queue", "TEST")
                                }
                            )
                            
                            if (result != null && !result.startsWith("Ошибка")) {
                                results["yandex_tracker"] = result
                                AppLogger.i(TAG, "Successfully called yandex_tracker tool, result: ${result.take(100)}...")
                            } else {
                                AppLogger.w(TAG, "Failed to get result from yandex_tracker: $result")
                            }
                        }
                        
                        break // Используем первый найденный сервер
                    }
                } catch (e: Exception) {
                    AppLogger.e(TAG, "Error checking tools on server $serverName: ${e.message}", e)
                }
            }
        }
        
        return results
    }
    
    private fun generateId(): String {
        return "${currentTimeMillis()}_${Random.nextLong(0, Long.MAX_VALUE)}"
    }
}

