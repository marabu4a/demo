package org.example.demo.chat

import androidx.compose.runtime.*
import kotlinx.coroutines.*
import kotlin.random.Random

enum class DialogueMode {
    NORMAL,      // Обычный режим - все сообщения хранятся
    SUMMARY      // Режим сжатия - каждые N сообщений создается summary
}

class ChatViewModel(
    private val apiClientManager: AiApiClientManager
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
                
                // Формируем список сообщений для отправки:
                // 1. Загруженная история (если есть) - не показывается в UI
                // 2. Если есть summary, добавляем его как системное сообщение
                // 3. Исключаем сообщения пользователя, которые уже сжаты в summary
                // 4. Добавляем остальные сообщения (ответы AI и новые сообщения пользователя)
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
                    // Добавляем сообщения из сессии, исключая сжатые сообщения (и пользователя, и AI)
                    _session.value.messages.forEach { message ->
                        // Включаем только те сообщения, которые не сжаты
                        if (message.id !in _compressedMessageIds) {
                            add(message)
                        }
                    }
                }
                
                // После первого использования загруженной истории удаляем её
                if (_loadedHistory != null) {
                    _loadedHistory = null
                    _loadedHistoryUserMessagesCount = 0
                    AppLogger.d(TAG, "Loaded history used and cleared")
                }
                
                // Подсчитываем общее количество сообщений в запросе
                val totalMessagesCount = messagesToSend.size
                
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
                    AppLogger.i(TAG, "Loaded history summary for model: $modelName, user messages: ${savedHistory.userMessagesCount} (not shown in UI)")
                    
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
    
    private fun generateId(): String {
        return "${currentTimeMillis()}_${Random.nextLong(0, Long.MAX_VALUE)}"
    }
}

