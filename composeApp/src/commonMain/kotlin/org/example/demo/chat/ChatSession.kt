package org.example.demo.chat

data class ChatSession(
    val id: String = generateSessionId(),
    val messages: MutableList<Message> = mutableListOf()
) {
    companion object {
        private fun generateSessionId(): String {
            return "session_${currentTimeMillis()}"
        }
    }
}

