package org.example.demo.chat

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.random.Random

interface AiApiClient {
    suspend fun sendMessage(messages: List<Message>, temperature: Float = 0.0f): String
    suspend fun getAccessToken(authorizationKey: String): String
}

// Реализация для AI API (GigaChat/OpenAI)
class OpenAIApiClient(
    private val httpClient: HttpClient
) : AiApiClient {
    
    private var accessToken: String = ""
    private var tokenExpiresAt: Long? = null
    
    fun setAccessToken(token: String, expiresAt: Long? = null) {
        accessToken = token
        tokenExpiresAt = expiresAt
        AppLogger.d(TAG, "Access token set, expires_at: $expiresAt")
    }
    
    private suspend fun isTokenExpired(): Boolean {
        if (tokenExpiresAt == null) return false
        val currentTime = currentTimeMillis()
        // Обновляем токен за 5 минут до истечения
        val bufferTime = 5 * 60 * 1000L // 5 минут в миллисекундах
        return currentTime >= (tokenExpiresAt!! - bufferTime)
    }
    
    private suspend fun refreshTokenIfNeeded() {
        if (accessToken.isBlank() || isTokenExpired()) {
            AppLogger.d(TAG, "Token expired or missing, refreshing...")
            val newToken = getAccessToken()
            // expires_at будет установлен в getAccessToken
        }
    }
    
    companion object {
        private const val API_URL = "https://gigachat.devices.sberbank.ru/api/v1/chat/completions"
        private const val OAUTH_URL = "https://ngw.devices.sberbank.ru:9443/api/v2/oauth"
        private const val TAG = "AiApiClient"
        private const val MODEL = "GigaChat"
        
        private const val AUTHORIZATION_KEY = "MDE5YWRhYTktNmIxZi03M2QyLWIzODctOTQ4NWIzOTdhNTVmOjI0MGY0MzcxLTc2ZWYtNGMzMC04YTk5LTFkYjA1ZjgwNWQ1NQ=="
    }
    
    @Serializable
    data class ChatRequest(
        val model: String,
        val messages: List<ChatMessage>,
        val stream: Boolean = false,
        val repetition_penalty: Double = 1.0,
        val temperature: Float = 0.0f
    )
    
    @Serializable
    data class ChatMessage(
        val role: String,
        val content: String
    )
    
    @Serializable
    data class ChatResponse(
        val choices: List<Choice>,
        val created: Long? = null,
        val model: String? = null,
        val `object`: String? = null,
        val usage: Usage? = null
    )
    
    @Serializable
    data class Choice(
        val finish_reason: String? = null,
        val index: Int? = null,
        val message: ChatMessage
    )
    
    @Serializable
    data class Usage(
        val completion_tokens: Int? = null,
        val prompt_tokens: Int? = null,
        val system_tokens: Int? = null,
        val total_tokens: Int? = null
    )
    
    @Serializable
    data class OAuthResponse(
        val access_token: String,
        val expires_at: Long? = null,
        val token_type: String? = null
    )
    
    suspend fun getAccessToken(): String {
        val (token, expiresAt) = getAccessTokenWithExpiry(AUTHORIZATION_KEY)
        setAccessToken(token, expiresAt)
        return token
    }
    
    override suspend fun getAccessToken(authorizationKey: String): String {
        val (token, expiresAt) = getAccessTokenWithExpiry(authorizationKey)
        setAccessToken(token, expiresAt)
        return token
    }
    
    private suspend fun getAccessTokenWithExpiry(authorizationKey: String): Pair<String, Long?> = withContext(Dispatchers.Default) {
        try {
            AppLogger.d(TAG, "Requesting access token from: $OAUTH_URL")
            
            // Генерируем UUID v4 для RqUID
            val rqUID = generateUUIDv4()
            AppLogger.d(TAG, "Generated RqUID: $rqUID")
            
            val response: OAuthResponse = try {
                // Формируем form-urlencoded данные
                val formData = parameters {
                    append("scope", "GIGACHAT_API_PERS")
                }
                
                AppLogger.d(TAG, "Sending OAuth request with scope: GIGACHAT_API_PERS, RqUID: $rqUID")
                
                // Authorization Key уже в base64 формате, используем как Basic Auth
                val httpResponse = httpClient.post(OAUTH_URL) {
                    header(HttpHeaders.Authorization, "Basic $authorizationKey")
                    header("RqUID", rqUID) // Обязательный заголовок с UUID v4
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded)
                    header(HttpHeaders.Accept, ContentType.Application.Json)
                    setBody(FormDataContent(formData))
                }
                
                AppLogger.d(TAG, "Request sent. Authorization header (first 30 chars): ${authorizationKey.take(30)}...")
                
                val statusCode = httpResponse.status.value
                val contentType = httpResponse.headers[HttpHeaders.ContentType]
                AppLogger.d(TAG, "OAuth response status: $statusCode, Content-Type: $contentType")
                
                if (statusCode !in 200..299) {
                    val errorBody = try {
                        httpResponse.body<String>()
                    } catch (e: Exception) {
                        "Unable to read error body: ${e.message}"
                    }
                    
                    AppLogger.e(TAG, "OAuth HTTP error $statusCode. Response body: ${errorBody.take(500)}")
                    AppLogger.e(TAG, "Request URL: $OAUTH_URL")
                    AppLogger.e(TAG, "Request body: scope=GIGACHAT_API_PERS")
                    AppLogger.e(TAG, "Authorization header: Basic ${authorizationKey.take(30)}...")
                    
                    when (statusCode) {
                        401 -> throw Exception("Unauthorized: Invalid authorization key. Check if the key is correct.")
                        403 -> throw Exception("Forbidden: Access denied. Check your authorization key")
                        400 -> throw Exception("Bad Request: Invalid request format. Response: $errorBody. Check if scope parameter and Authorization format are correct.")
                        else -> throw Exception("HTTP $statusCode: $errorBody")
                    }
                }
                
                if (contentType?.contains("application/json") != true) {
                    val bodyPreview = try {
                        httpResponse.body<String>().take(200)
                    } catch (e: Exception) {
                        "Unable to read body"
                    }
                    AppLogger.e(TAG, "Unexpected Content-Type: $contentType. Body preview: $bodyPreview")
                    throw Exception("Server returned non-JSON response (Content-Type: $contentType)")
                }
                
                httpResponse.body<OAuthResponse>()
            } catch (httpException: Exception) {
                AppLogger.e(TAG, "OAuth request failed", httpException)
                throw httpException
            }
            
            if (response.access_token.isBlank()) {
                AppLogger.e(TAG, "Access token is empty in OAuth response")
                throw Exception("Access token is empty in response")
            }
            
            // Убираем пробелы и переносы строк из токена
            val cleanToken = response.access_token.trim()
            
            AppLogger.d(TAG, "Access token received successfully, expires_at: ${response.expires_at}")
            AppLogger.d(TAG, "Token length: ${cleanToken.length}")
            
            // Возвращаем токен и expires_at
            Pair(cleanToken, response.expires_at)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to get access token: ${e.message}", e)
            throw Exception("Failed to get access token: ${e.message}", e)
        }
    }
    
    override suspend fun sendMessage(messages: List<Message>, temperature: Float): String = withContext(Dispatchers.Default) {
        try {
            // Проверяем и обновляем токен при необходимости
            refreshTokenIfNeeded()
            
            AppLogger.d(TAG, "Sending message to API: $API_URL")
            AppLogger.d(TAG, "Messages count: ${messages.size}, temperature: $temperature")
            
            val chatMessages = messages.map { message ->
                ChatMessage(
                    role = when (message.role) {
                        MessageRole.USER -> "user"
                        MessageRole.ASSISTANT -> "assistant"
                        MessageRole.SYSTEM -> "system"
                    },
                    content = message.content
                )
            }
            
            val request = ChatRequest(
                model = MODEL,
                messages = chatMessages,
                stream = false,
                repetition_penalty = 1.0,
                temperature = temperature
            )
            
            AppLogger.d(TAG, "Request body: model=$MODEL, messages=${chatMessages.size}, stream=false")
            
            val response: ChatResponse = try {
                val httpResponse = httpClient.post(API_URL) {
                    header(HttpHeaders.Authorization, "Bearer $accessToken")
                    header(HttpHeaders.ContentType, ContentType.Application.Json)
                    header(HttpHeaders.Accept, ContentType.Application.Json)
                    setBody(request)
                }
                
                val statusCode = httpResponse.status.value
                val contentType = httpResponse.headers[HttpHeaders.ContentType]
                AppLogger.d(TAG, "Response status: $statusCode, Content-Type: $contentType")
                
                if (statusCode !in 200..299) {
                    // Читаем тело ответа для ошибок
                    val errorBody = try {
                        httpResponse.body<String>()
                    } catch (e: Exception) {
                        "Unable to read error body: ${e.message}"
                    }
                    
                    AppLogger.e(TAG, "HTTP error $statusCode. Response body: ${errorBody.take(500)}")
                    
                    when (statusCode) {
                        401 -> {
                            // Токен истек или недействителен, пытаемся обновить
                            AppLogger.w(TAG, "Received 401, attempting to refresh token...")
                            try {
                                refreshTokenIfNeeded()
                                // Повторяем запрос с новым токеном
                                AppLogger.d(TAG, "Retrying request with refreshed token")
                                return@withContext sendMessage(messages, temperature)
                            } catch (refreshError: Exception) {
                                AppLogger.e(TAG, "Failed to refresh token", refreshError)
                                throw Exception("Unauthorized: Invalid access token and failed to refresh")
                            }
                        }
                        403 -> throw Exception("Forbidden: Access denied. Check your access token and API endpoint")
                        404 -> throw Exception("Not Found: API endpoint not found. Check the URL: $API_URL")
                        429 -> throw Exception("Too Many Requests: Rate limit exceeded")
                        500, 502, 503, 504 -> throw Exception("Server Error ($statusCode): $errorBody")
                        else -> throw Exception("HTTP $statusCode: $errorBody")
                    }
                }
                
                // Проверяем Content-Type перед десериализацией
                if (contentType?.contains("application/json") != true) {
                    val bodyPreview = try {
                        httpResponse.body<String>().take(200)
                    } catch (e: Exception) {
                        "Unable to read body"
                    }
                    AppLogger.e(TAG, "Unexpected Content-Type: $contentType. Body preview: $bodyPreview")
                    throw Exception("Server returned non-JSON response (Content-Type: $contentType)")
                }
                
                httpResponse.body<ChatResponse>()
            } catch (httpException: Exception) {
                AppLogger.e(TAG, "HTTP request failed", httpException)
                throw httpException
            }
            
            AppLogger.d(TAG, "Response received, choices count: ${response.choices.size}")
            
            // Логируем информацию об использовании токенов, если доступна
            response.usage?.let { usage ->
                AppLogger.d(TAG, "Token usage: prompt=${usage.prompt_tokens}, completion=${usage.completion_tokens}, total=${usage.total_tokens}")
            }
            
            val content = response.choices.firstOrNull()?.message?.content
            if (content == null) {
                AppLogger.e(TAG, "No response content in API response")
                throw Exception("No response from AI")
            }
            
            val finishReason = response.choices.firstOrNull()?.finish_reason
            AppLogger.d(TAG, "Response content length: ${content.length}, finish_reason: $finishReason")
            AppLogger.i(TAG, "Received AI message: $content")
            content
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to get AI response: ${e.message}", e)
            throw Exception("Failed to get AI response: ${e.message}", e)
        }
    }
    
    /**
     * Генерирует UUID v4 в формате: xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx
     * где x - любая hex цифра, y - один из 8, 9, a, b
     */
    private fun generateUUIDv4(): String {
        val chars = "0123456789abcdef"
        val uuid = StringBuilder(36)
        
        for (i in 0 until 36) {
            when (i) {
                8, 13, 18, 23 -> uuid.append('-')
                14 -> uuid.append('4') // версия 4
                19 -> uuid.append(chars[Random.nextInt(4) + 8]) // 8, 9, a, b
                else -> uuid.append(chars[Random.nextInt(16)])
            }
        }
        
        return uuid.toString()
    }
}

// Фабрика для создания HTTP клиента
expect fun createHttpClient(): HttpClient

