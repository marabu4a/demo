package org.example.demo.chat

import androidx.compose.runtime.*
import kotlinx.coroutines.*
import kotlin.random.Random

class ChatViewModel(
    private val apiClientManager: AiApiClientManager
) {
    companion object {
        private const val TAG = "ChatViewModel"
    }
    
    private val viewModelScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    
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
                
                AppLogger.d(TAG, "Calling AI API (${_selectedModel.value.displayName}) with ${_session.value.messages.size} messages, temperature: ${_temperature.value}, maxTokens: $maxTokens")
                
                // Засекаем время начала запроса
                val startTime = currentTimeMillis()
                val response = client.sendMessage(_session.value.messages, _temperature.value, maxTokens)
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
                    totalTokens = totalTokens
                )
                _session.value.messages.add(assistantMessage)
                AppLogger.i(TAG, "Message successfully processed and added to session (response time: ${responseTime}ms)")
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
        _session.value = ChatSession()
        _errorMessage.value = null
    }
    
    fun dismissError() {
        _errorMessage.value = null
    }
    
    private fun generateId(): String {
        return "${currentTimeMillis()}_${Random.nextLong(0, Long.MAX_VALUE)}"
    }
}

