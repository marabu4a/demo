package org.example.demo.chat

import platform.Foundation.NSFileManager
import platform.Foundation.NSHomeDirectory
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.writeToFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private fun getHistoryDirectory(): String {
    val homeDir = NSHomeDirectory()
    val historyDir = "$homeDir/.demo_chat_history"
    val fileManager = NSFileManager.defaultManager
    if (!fileManager.fileExistsAtPath(historyDir)) {
        fileManager.createDirectoryAtPath(historyDir, true, null, null)
    }
    return historyDir
}

private fun getHistoryFilePath(modelName: String): String {
    val safeModelName = modelName.replace(Regex("[^a-zA-Z0-9_-]"), "_")
    return "${getHistoryDirectory()}/history_$safeModelName.json"
}

actual suspend fun saveChatHistory(modelName: String, summary: String, userMessagesCount: Int): Boolean = withContext(Dispatchers.Default) {
    try {
        val history = SavedChatHistory(
            modelName = modelName,
            summary = summary,
            userMessagesCount = userMessagesCount
        )
        val jsonString = serializeHistory(history)
        val filePath = getHistoryFilePath(modelName)
        val nsString = NSString.create(string = jsonString)
        val success = nsString.writeToFile(filePath, atomically = true, encoding = NSUTF8StringEncoding, error = null)
        if (success) {
            AppLogger.d("ChatHistoryStorage", "Saved history summary for model: $modelName, user messages: $userMessagesCount")
        }
        success
    } catch (e: Exception) {
        AppLogger.e("ChatHistoryStorage", "Failed to save history: ${e.message}", e)
        false
    }
}

actual suspend fun loadChatHistory(modelName: String): SavedChatHistory? = withContext(Dispatchers.Default) {
    try {
        val filePath = getHistoryFilePath(modelName)
        val fileManager = NSFileManager.defaultManager
        if (!fileManager.fileExistsAtPath(filePath)) return@withContext null
        
        val nsString = NSString.stringWithContentsOfFile(filePath, NSUTF8StringEncoding, null)
        val jsonString = nsString?.toString() ?: return@withContext null
        val history = deserializeHistory(jsonString)
        AppLogger.d("ChatHistoryStorage", "Loaded history summary for model: $modelName, user messages: ${history?.userMessagesCount ?: 0}")
        history
    } catch (e: Exception) {
        AppLogger.e("ChatHistoryStorage", "Failed to load history: ${e.message}", e)
        null
    }
}

actual suspend fun deleteChatHistory(modelName: String): Boolean = withContext(Dispatchers.Default) {
    try {
        val filePath = getHistoryFilePath(modelName)
        val fileManager = NSFileManager.defaultManager
        if (fileManager.fileExistsAtPath(filePath)) {
            fileManager.removeItemAtPath(filePath, null)
        }
        AppLogger.d("ChatHistoryStorage", "Deleted history for model: $modelName")
        true
    } catch (e: Exception) {
        AppLogger.e("ChatHistoryStorage", "Failed to delete history: ${e.message}", e)
        false
    }
}

actual suspend fun hasChatHistory(modelName: String): Boolean = withContext(Dispatchers.Default) {
    try {
        val filePath = getHistoryFilePath(modelName)
        val fileManager = NSFileManager.defaultManager
        fileManager.fileExistsAtPath(filePath)
    } catch (e: Exception) {
        AppLogger.e("ChatHistoryStorage", "Failed to check history: ${e.message}", e)
        false
    }
}


