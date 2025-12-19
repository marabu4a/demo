package org.example.demo.chat

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private fun getHistoryDirectory(): File {
    val userHome = System.getProperty("user.home")
    val historyDir = File(userHome, ".demo_chat_history")
    if (!historyDir.exists()) {
        historyDir.mkdirs()
    }
    return historyDir
}

private fun getHistoryFile(modelName: String): File {
    val safeModelName = modelName.replace(Regex("[^a-zA-Z0-9_-]"), "_")
    return File(getHistoryDirectory(), "history_$safeModelName.json")
}

actual suspend fun saveChatHistory(modelName: String, summary: String, userMessagesCount: Int): Boolean = withContext(Dispatchers.IO) {
    try {
        val history = SavedChatHistory(
            modelName = modelName,
            summary = summary,
            userMessagesCount = userMessagesCount
        )
        val jsonString = serializeHistory(history)
        val file = getHistoryFile(modelName)
        file.writeText(jsonString)
        AppLogger.d("ChatHistoryStorage", "Saved history summary for model: $modelName, user messages: $userMessagesCount")
        true
    } catch (e: Exception) {
        AppLogger.e("ChatHistoryStorage", "Failed to save history: ${e.message}", e)
        false
    }
}

actual suspend fun loadChatHistory(modelName: String): SavedChatHistory? = withContext(Dispatchers.IO) {
    try {
        val file = getHistoryFile(modelName)
        if (!file.exists()) return@withContext null
        val jsonString = file.readText()
        val history = deserializeHistory(jsonString)
        AppLogger.d("ChatHistoryStorage", "Loaded history summary for model: $modelName, user messages: ${history?.userMessagesCount ?: 0}")
        history
    } catch (e: Exception) {
        AppLogger.e("ChatHistoryStorage", "Failed to load history: ${e.message}", e)
        null
    }
}

actual suspend fun deleteChatHistory(modelName: String): Boolean = withContext(Dispatchers.IO) {
    try {
        val file = getHistoryFile(modelName)
        if (file.exists()) {
            file.delete()
        }
        AppLogger.d("ChatHistoryStorage", "Deleted history for model: $modelName")
        true
    } catch (e: Exception) {
        AppLogger.e("ChatHistoryStorage", "Failed to delete history: ${e.message}", e)
        false
    }
}

actual suspend fun hasChatHistory(modelName: String): Boolean = withContext(Dispatchers.IO) {
    try {
        val file = getHistoryFile(modelName)
        file.exists()
    } catch (e: Exception) {
        AppLogger.e("ChatHistoryStorage", "Failed to check history: ${e.message}", e)
        false
    }
}






