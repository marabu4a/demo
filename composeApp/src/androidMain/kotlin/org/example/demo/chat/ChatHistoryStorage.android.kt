package org.example.demo.chat

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Глобальная переменная для хранения контекста (устанавливается при инициализации)
private var appContext: Context? = null

fun setAppContext(context: Context) {
    appContext = context.applicationContext
}

private fun getSharedPreferences(): SharedPreferences? {
    return appContext?.getSharedPreferences("chat_history", Context.MODE_PRIVATE)
}

actual suspend fun saveChatHistory(modelName: String, summary: String, userMessagesCount: Int): Boolean = withContext(Dispatchers.IO) {
    try {
        val prefs = getSharedPreferences() ?: return@withContext false
        val history = SavedChatHistory(
            modelName = modelName,
            summary = summary,
            userMessagesCount = userMessagesCount
        )
        val jsonString = serializeHistory(history)
        prefs.edit().putString("history_$modelName", jsonString).apply()
        AppLogger.d("ChatHistoryStorage", "Saved history summary for model: $modelName, user messages: $userMessagesCount")
        true
    } catch (e: Exception) {
        AppLogger.e("ChatHistoryStorage", "Failed to save history: ${e.message}", e)
        false
    }
}

actual suspend fun loadChatHistory(modelName: String): SavedChatHistory? = withContext(Dispatchers.IO) {
    try {
        val prefs = getSharedPreferences() ?: return@withContext null
        val jsonString = prefs.getString("history_$modelName", null) ?: return@withContext null
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
        val prefs = getSharedPreferences() ?: return@withContext false
        prefs.edit().remove("history_$modelName").apply()
        AppLogger.d("ChatHistoryStorage", "Deleted history for model: $modelName")
        true
    } catch (e: Exception) {
        AppLogger.e("ChatHistoryStorage", "Failed to delete history: ${e.message}", e)
        false
    }
}

actual suspend fun hasChatHistory(modelName: String): Boolean = withContext(Dispatchers.IO) {
    try {
        val prefs = getSharedPreferences() ?: return@withContext false
        prefs.contains("history_$modelName")
    } catch (e: Exception) {
        AppLogger.e("ChatHistoryStorage", "Failed to check history: ${e.message}", e)
        false
    }
}


