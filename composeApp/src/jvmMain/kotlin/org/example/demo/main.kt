package org.example.demo

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import kotlinx.coroutines.runBlocking

fun main(args: Array<String>) {
    // Если передан аргумент "mcp-test", запускаем тест MCP вместо UI
    if (args.isNotEmpty() && args[0] == "mcp-test") {
        testMcp()
        return
    }
    
    // Если передан аргумент "mcp-agent", запускаем пример агента с MCP
    if (args.isNotEmpty() && args[0] == "mcp-agent") {
        runMcpAgentExample()
        return
    }
    
    // Иначе запускаем обычное Compose приложение
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "demo",
        ) {
            App()
        }
    }
}