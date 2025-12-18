import org.gradle.jvm.toolchain.JavaLanguageVersion

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.ktor)
    application
}

group = "org.example.demo"
version = "1.0.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(24))
    }
}

application {
    // Используем MCP сервер вместо обычного Application
    mainClass.set("org.example.demo.McpServerKt")

    val isDevelopment: Boolean = project.ext.has("development")
    applicationDefaultJvmArgs = listOf("-Dio.ktor.development=$isDevelopment")
}

dependencies {
    implementation(projects.shared)
    implementation(libs.logback)
    implementation(libs.ktor.serverCore)
    implementation(libs.ktor.serverNetty)
    // Для JSON сериализации
    implementation(libs.kotlinx.serialization.json)
    // Для Content Negotiation
    implementation("io.ktor:ktor-server-content-negotiation:${libs.versions.ktor.get()}")
    // Для JSON сериализации в Ktor сервере
    implementation("io.ktor:ktor-serialization-kotlinx-json:${libs.versions.ktor.get()}")
    // Для CORS
    implementation("io.ktor:ktor-server-cors:${libs.versions.ktor.get()}")
    // Для корутин (версия из gradle/libs.versions.toml: kotlinx-coroutines = "1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    // Для HTTP клиента
    implementation("io.ktor:ktor-client-core:${libs.versions.ktor.get()}")
    implementation("io.ktor:ktor-client-cio:${libs.versions.ktor.get()}")
    implementation("io.ktor:ktor-client-okhttp:${libs.versions.ktor.get()}")
    implementation("io.ktor:ktor-client-content-negotiation:${libs.versions.ktor.get()}")
    implementation("io.ktor:ktor-serialization-kotlinx-json:${libs.versions.ktor.get()}")
    testImplementation(libs.ktor.serverTestHost)
    testImplementation(libs.kotlin.testJunit)
}

// Задача для запуска агента напоминаний
tasks.register<JavaExec>("runReminderAgent") {
    group = "application"
    description = "Запускает агента напоминаний 24/7"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("org.example.demo.ReminderAgentKt")
    
    // Параметры по умолчанию: проверка каждые 60 минут, сводка каждые 6 часов
    args = listOf("60", "6", "http://localhost:8080/mcp")
    
    // Можно переопределить через --args
    if (project.hasProperty("agentArgs")) {
        args = (project.property("agentArgs") as String).split(" ")
    }
}

// Задача для запуска AI-агента напоминаний
tasks.register<JavaExec>("runAiReminderAgent") {
    group = "application"
    description = "Запускает AI-агента напоминаний 24/7 (использует AI для анализа сводки)"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("org.example.demo.AiReminderAgentKt")
    
    // Параметры по умолчанию: проверка каждые 60 минут, сводка каждые 6 часов
    // GIGACHAT_API_KEY должен быть установлен в переменных окружения
    args = listOf("60", "6", "http://localhost:8080/mcp")
    
    // Можно переопределить через --args
    if (project.hasProperty("agentArgs")) {
        args = (project.property("agentArgs") as String).split(" ")
    }
    
    // Передаем переменные окружения
    environment("GIGACHAT_API_KEY", System.getenv("GIGACHAT_API_KEY") ?: "")
}

// Задача для быстрого тестирования AI-агента (1 минута)
tasks.register<JavaExec>("testAiReminderAgent") {
    group = "application"
    description = "Тестовый запуск AI-агента: проверка каждую минуту, сводка каждые 2 минуты"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("org.example.demo.AiReminderAgentKt")
    
    // Тестовые параметры: проверка каждую минуту, сводка каждые 2 минуты (0.033 часа)
    args = listOf("1", "0.033", "http://localhost:8080/mcp")
    
    environment("GIGACHAT_API_KEY", System.getenv("GIGACHAT_API_KEY") ?: "")
}

// Задача для запуска cron-агента (проверка просроченных)
tasks.register<JavaExec>("runCronCheckDue") {
    group = "application"
    description = "Cron-агент: проверка просроченных напоминаний (одноразовый запуск)"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("org.example.demo.CronReminderAgentKt")
    args = listOf("check_due", "http://localhost:8080/mcp")
}

// Задача для запуска cron-агента (полная сводка)
tasks.register<JavaExec>("runCronSummary") {
    group = "application"
    description = "Cron-агент: полная сводка по напоминаниям (одноразовый запуск)"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("org.example.demo.CronReminderAgentKt")
    args = listOf("summary", "http://localhost:8080/mcp")
}