package org.example.demo.chat

import kotlinx.serialization.Serializable

@Serializable
data class Message(
    val id: String,
    val content: String,
    val role: MessageRole,
    val timestamp: Long = currentTimeMillis(),
    val responseTimeMs: Long? = null,
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val totalTokens: Int? = null,
    val sentMessagesCount: Int? = null // Количество сообщений, отправленных в запросе (для режима SUMMARY)
)

enum class MessageRole {
    USER,
    ASSISTANT,
    SYSTEM
}

