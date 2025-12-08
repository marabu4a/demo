package org.example.demo.chat

import androidx.compose.runtime.*
import kotlinx.coroutines.*
import kotlin.random.Random

class ChatViewModel(
    private val aiApiClient: OpenAIApiClient
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
    
    fun toggleSystemRole() {
        _useSystemRole.value = !_useSystemRole.value
        AppLogger.d(TAG, "System role mode: ${_useSystemRole.value}")
    }
    
    fun setTemperature(value: Float) {
        _temperature.value = value
        AppLogger.d(TAG, "Temperature set to: $value")
    }
    
    fun setAccessToken(token: String, expiresAt: Long? = null) {
        _accessToken.value = token
        aiApiClient.setAccessToken(token, expiresAt)
    }
    
    fun getAccessTokenFromKey() {
        _isLoadingToken.value = true
        _errorMessage.value = null
        
        viewModelScope.launch {
            try {
                AppLogger.d(TAG, "Requesting access token with built-in authorization key")
                val token = aiApiClient.getAccessToken()
                // expires_at уже сохранен в aiApiClient при получении токена
                AppLogger.i(TAG, "Access token obtained successfully")
            } catch (e: Exception) {
                val errorMsg = e.message ?: "Failed to get access token"
                AppLogger.e(TAG, "Error getting access token: $errorMsg", e)
                _errorMessage.value = errorMsg
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
                // Если токен пустой, пытаемся получить его автоматически
                if (_accessToken.value.isBlank()) {
                    AppLogger.d(TAG, "Access token is blank, attempting to get it automatically")
                    try {
                        val token = aiApiClient.getAccessToken()
                        setAccessToken(token)
                        AppLogger.i(TAG, "Access token obtained automatically before sending message")
                    } catch (e: Exception) {
                        val errorMsg = "Failed to get access token: ${e.message ?: "Unknown error"}"
                        AppLogger.e(TAG, errorMsg, e)
                        _errorMessage.value = errorMsg
                        _session.value.messages.removeLast() // Удаляем сообщение пользователя при ошибке
                        _isLoading.value = false
                        return@launch
                    }
                }
                
                AppLogger.d(TAG, "Calling AI API with ${_session.value.messages.size} messages, temperature: ${_temperature.value}")
                val response = aiApiClient.sendMessage(_session.value.messages, _temperature.value)
                AppLogger.d(TAG, "Received response from AI, length: ${response.length}")
                AppLogger.i(TAG, "AI Response: $response")
                
                val assistantMessage = Message(
                    id = generateId(),
                    content = response,
                    role = MessageRole.ASSISTANT
                )
                _session.value.messages.add(assistantMessage)
                AppLogger.i(TAG, "Message successfully processed and added to session")
            } catch (e: Exception) {
                val errorMsg = e.message ?: "Failed to get response"
                AppLogger.e(TAG, "Error sending message: $errorMsg", e)
                _errorMessage.value = errorMsg
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

