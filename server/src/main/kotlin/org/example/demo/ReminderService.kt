package org.example.demo

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Модель напоминания
 */
@Serializable
data class Reminder(
    val id: String,
    val title: String,
    val description: String? = null,
    val createdAt: Long,
    val dueDate: Long? = null, // Время, когда нужно напомнить (timestamp в миллисекундах)
    val priority: String = "normal", // low, normal, high
    val category: String? = null,
    val completed: Boolean = false,
    val completedAt: Long? = null
)

/**
 * Сервис для управления напоминаниями
 * Хранит данные в JSON файле
 */
class ReminderService(private val storageFile: File = File("reminders.json")) {
    private val mutex = Mutex()
    private val json = Json { 
        ignoreUnknownKeys = true
        prettyPrint = true
    }
    
    init {
        // Создаем файл, если его нет
        if (!storageFile.exists()) {
            storageFile.writeText("[]")
        }
    }
    
    /**
     * Загружает все напоминания из файла
     */
    private suspend fun loadReminders(): MutableList<Reminder> {
        return mutex.withLock {
            try {
                val content = storageFile.readText()
                if (content.isBlank()) {
                    return mutableListOf()
                }
                json.decodeFromString<MutableList<Reminder>>(content)
            } catch (e: Exception) {
                println("Error loading reminders: ${e.message}")
                mutableListOf()
            }
        }
    }
    
    /**
     * Сохраняет напоминания в файл
     */
    private suspend fun saveReminders(reminders: List<Reminder>) {
        mutex.withLock {
            try {
                val jsonString = json.encodeToString(reminders)
                storageFile.writeText(jsonString)
            } catch (e: Exception) {
                println("Error saving reminders: ${e.message}")
                throw e
            }
        }
    }
    
    /**
     * Создает новое напоминание
     */
    suspend fun createReminder(
        title: String,
        description: String? = null,
        dueDate: Long? = null,
        priority: String = "normal",
        category: String? = null
    ): Reminder {
        val reminders = loadReminders()
        val id = "reminder_${System.currentTimeMillis()}_${reminders.size}"
        val reminder = Reminder(
            id = id,
            title = title,
            description = description,
            createdAt = System.currentTimeMillis(),
            dueDate = dueDate,
            priority = priority,
            category = category,
            completed = false
        )
        reminders.add(reminder)
        saveReminders(reminders)
        return reminder
    }
    
    /**
     * Получает все напоминания
     */
    suspend fun getAllReminders(includeCompleted: Boolean = true): List<Reminder> {
        val reminders = loadReminders()
        return if (includeCompleted) {
            reminders
        } else {
            reminders.filter { !it.completed }
        }
    }
    
    /**
     * Получает напоминание по ID
     */
    suspend fun getReminderById(id: String): Reminder? {
        val reminders = loadReminders()
        return reminders.find { it.id == id }
    }
    
    /**
     * Удаляет напоминание
     */
    suspend fun deleteReminder(id: String): Boolean {
        val reminders = loadReminders()
        val removed = reminders.removeIf { it.id == id }
        if (removed) {
            saveReminders(reminders)
        }
        return removed
    }
    
    /**
     * Отмечает напоминание как выполненное
     */
    suspend fun completeReminder(id: String): Boolean {
        val reminders = loadReminders()
        val reminder = reminders.find { it.id == id }
        if (reminder != null && !reminder.completed) {
            val index = reminders.indexOf(reminder)
            reminders[index] = reminder.copy(
                completed = true,
                completedAt = System.currentTimeMillis()
            )
            saveReminders(reminders)
            return true
        }
        return false
    }
    
    /**
     * Получает напоминания, которые должны быть показаны (dueDate прошло и не выполнены)
     */
    suspend fun getDueReminders(): List<Reminder> {
        val now = System.currentTimeMillis()
        val reminders = loadReminders()
        return reminders.filter { 
            !it.completed && 
            it.dueDate != null && 
            it.dueDate <= now 
        }
    }
    
    /**
     * Получает сводку по напоминаниям
     */
    suspend fun getSummary(): String {
        val reminders = loadReminders()
        val total = reminders.size
        val completed = reminders.count { it.completed }
        val pending = total - completed
        val overdue = reminders.count { 
            !it.completed && 
            it.dueDate != null && 
            it.dueDate < System.currentTimeMillis() 
        }
        val dueToday = reminders.count {
            !it.completed &&
            it.dueDate != null &&
            it.dueDate >= System.currentTimeMillis() &&
            it.dueDate <= System.currentTimeMillis() + 24 * 60 * 60 * 1000 // следующие 24 часа
        }
        val highPriority = reminders.count { 
            !it.completed && 
            it.priority == "high" 
        }
        
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault())
        
        val summary = buildString {
            appendLine("📋 СВОДКА ПО НАПОМИНАНИЯМ")
            appendLine("=".repeat(40))
            appendLine("Всего напоминаний: $total")
            appendLine("✅ Выполнено: $completed")
            appendLine("⏳ Ожидает: $pending")
            appendLine("🔴 Просрочено: $overdue")
            appendLine("📅 На сегодня: $dueToday")
            appendLine("⚡ Высокий приоритет: $highPriority")
            appendLine()
            
            if (overdue > 0) {
                appendLine("⚠️ ПРОСРОЧЕННЫЕ НАПОМИНАНИЯ:")
                reminders.filter { 
                    !it.completed && 
                    it.dueDate != null && 
                    it.dueDate < System.currentTimeMillis() 
                }.take(5).forEach { reminder ->
                    val dueDateStr = formatter.format(Instant.ofEpochMilli(reminder.dueDate!!))
                    appendLine("  • ${reminder.title} (было: $dueDateStr)")
                    if (reminder.description != null) {
                        appendLine("    ${reminder.description}")
                    }
                }
                appendLine()
            }
            
            if (dueToday > 0) {
                appendLine("📅 НА СЕГОДНЯ:")
                reminders.filter {
                    !it.completed &&
                    it.dueDate != null &&
                    it.dueDate >= System.currentTimeMillis() &&
                    it.dueDate <= System.currentTimeMillis() + 24 * 60 * 60 * 1000
                }.take(5).forEach { reminder ->
                    val dueDateStr = formatter.format(Instant.ofEpochMilli(reminder.dueDate!!))
                    appendLine("  • ${reminder.title} (до: $dueDateStr)")
                    if (reminder.description != null) {
                        appendLine("    ${reminder.description}")
                    }
                }
                appendLine()
            }
            
            if (highPriority > 0) {
                appendLine("⚡ ВЫСОКИЙ ПРИОРИТЕТ:")
                reminders.filter { 
                    !it.completed && 
                    it.priority == "high" 
                }.take(5).forEach { reminder ->
                    appendLine("  • ${reminder.title}")
                    if (reminder.description != null) {
                        appendLine("    ${reminder.description}")
                    }
                }
            }
        }
        
        return summary
    }
}

// Глобальный экземпляр сервиса
val reminderService = ReminderService()

