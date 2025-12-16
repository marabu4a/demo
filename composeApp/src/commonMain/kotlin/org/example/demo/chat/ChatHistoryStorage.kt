package org.example.demo.chat

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class SavedChatHistory(
    val modelName: String,
    val summary: String, // Сохраняем summary вместо полного списка сообщений
    val userMessagesCount: Int, // Количество сообщений пользователя в summary
    val savedAt: Long = currentTimeMillis()
)

expect suspend fun saveChatHistory(modelName: String, summary: String, userMessagesCount: Int): Boolean
expect suspend fun loadChatHistory(modelName: String): SavedChatHistory?
expect suspend fun deleteChatHistory(modelName: String): Boolean
expect suspend fun hasChatHistory(modelName: String): Boolean

// Вспомогательная функция для сериализации
fun serializeHistory(history: SavedChatHistory): String {
    val json = Json { ignoreUnknownKeys = true }
    return json.encodeToString(history)
}

// Вспомогательная функция для десериализации
fun deserializeHistory(jsonString: String): SavedChatHistory? {
    return try {
        val json = Json { ignoreUnknownKeys = true }
        json.decodeFromString<SavedChatHistory>(jsonString)
    } catch (e: Exception) {
        AppLogger.e("ChatHistoryStorage", "Failed to deserialize history: ${e.message}", e)
        null
    }
}


