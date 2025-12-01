package org.example.demo

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import org.example.demo.chat.*
import org.jetbrains.compose.ui.tooling.preview.Preview

@Composable
@Preview
fun App() {
    MaterialTheme {
        val httpClient = remember { createHttpClient() }
        val aiApiClient = remember { OpenAIApiClient(httpClient) }
        val viewModel = remember { ChatViewModel(aiApiClient) }
        
        ChatScreen(viewModel = viewModel)
    }
}