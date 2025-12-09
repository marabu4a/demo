package org.example.demo.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(viewModel: ChatViewModel) {
    val session by viewModel.session
    val isLoading by viewModel.isLoading
    val errorMessage by viewModel.errorMessage
    val accessToken by viewModel.accessToken
    val isLoadingToken by viewModel.isLoadingToken
    val useSystemRole by viewModel.useSystemRole
    val temperature by viewModel.temperature
    val selectedModel by viewModel.selectedModel
    val huggingFaceToken by viewModel.huggingFaceToken
    
    var messageText by remember { mutableStateOf("") }
    var showAccessTokenDialog by remember { mutableStateOf(false) }
    var showTemperatureDialog by remember { mutableStateOf(false) }
    var showModelDialog by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    
    // Автоматически получаем токен при первом запуске
    LaunchedEffect(Unit) {
        if (accessToken.isBlank() && !isLoadingToken) {
            viewModel.getAccessTokenFromKey()
        }
    }
    
    // Автопрокрутка к последнему сообщению
    LaunchedEffect(session.messages.size) {
        if (session.messages.isNotEmpty()) {
            scope.launch {
                listState.animateScrollToItem(session.messages.size - 1)
            }
        }
    }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Заголовок
        TopAppBar(
            title = { Text("AI Chat Bot") },
            actions = {
                TextButton(
                    onClick = { showModelDialog = true },
                    modifier = Modifier.padding(horizontal = 4.dp)
                ) {
                    Text(
                        text = selectedModel.displayName,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                IconButton(
                    onClick = { showTemperatureDialog = true },
                    modifier = Modifier.padding(horizontal = 4.dp)
                ) {
                    Text(
                        text = "T: ${String.format("%.1f", temperature)}",
                        fontSize = 12.sp,
                        color = if (temperature != 0.0f) 
                            MaterialTheme.colorScheme.primary 
                        else 
                            MaterialTheme.colorScheme.onSurface
                    )
                }
                IconButton(
                    onClick = { viewModel.toggleSystemRole() },
                    modifier = Modifier.padding(horizontal = 4.dp)
                ) {
                    Text(
                        text = if (useSystemRole) "System" else "User",
                        fontSize = 12.sp,
                        color = if (useSystemRole) 
                            MaterialTheme.colorScheme.primary 
                        else 
                            MaterialTheme.colorScheme.onSurface
                    )
                }
                IconButton(onClick = { viewModel.clearChat() }) {
                    Text("Clear", fontSize = 12.sp)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        )
        
        // Список сообщений
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (session.messages.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Start a conversation with AI",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            
            items(session.messages) { message ->
                MessageBubble(message = message)
            }
            
            if (isLoading) {
                item {
                    MessageBubble(
                        message = Message(
                            id = "loading",
                            content = "Thinking...",
                            role = MessageRole.ASSISTANT
                        ),
                        isLoading = true
                    )
                }
            }
        }
        
        // Поле ввода
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            OutlinedTextField(
                value = messageText,
                onValueChange = { messageText = it },
                modifier = Modifier
                    .weight(1f)
                    .defaultMinSize(minHeight = 56.dp),
                placeholder = { 
                    Text(
                        if (accessToken.isBlank() && isLoadingToken) 
                            "Getting access token..." 
                        else 
                            "Type a message..."
                    ) 
                },
                enabled = true,
                singleLine = false,
                maxLines = 4,
                shape = RoundedCornerShape(24.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(
                    onSend = {
                        if (messageText.isNotBlank() && !isLoading && accessToken.isNotBlank()) {
                            viewModel.sendMessage(messageText)
                            messageText = ""
                        }
                    }
                )
            )
            Button(
                onClick = {
                    viewModel.sendMessage(messageText)
                    messageText = ""
                },
                modifier = Modifier.height(56.dp),
                enabled = messageText.isNotBlank() && !isLoading
            ) {
                Text("Send")
            }
        }
    }
    
    // Диалог для ввода Access Token
    if (showAccessTokenDialog) {
        AccessTokenDialog(
            currentToken = accessToken,
            isLoadingToken = isLoadingToken,
            onDismiss = { showAccessTokenDialog = false },
            onConfirmToken = { token ->
                viewModel.setAccessToken(token)
                showAccessTokenDialog = false
            },
            onRefreshToken = {
                viewModel.getAccessTokenFromKey()
            }
        )
    }
    
    // Диалог для настройки Temperature
    if (showTemperatureDialog) {
        TemperatureDialog(
            currentTemperature = temperature,
            onDismiss = { showTemperatureDialog = false },
            onConfirm = { value ->
                viewModel.setTemperature(value)
                showTemperatureDialog = false
            }
        )
    }
    
    // Диалог для выбора модели
    if (showModelDialog) {
        ModelSelectionDialog(
            currentModel = selectedModel,
            onDismiss = { showModelDialog = false },
            onSelectModel = { model ->
                viewModel.setSelectedModel(model)
                showModelDialog = false
            }
        )
    }
    
    // Показ ошибок
    errorMessage?.let { error ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissError() },
            title = { Text("Error") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                ) {
                    val scrollState = rememberScrollState()
                    Text(
                        text = error,
                        modifier = Modifier
                            .verticalScroll(scrollState)
                            .fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissError() }) {
                    Text("OK")
                }
            }
        )
    }
}

@Composable
fun MessageBubble(message: Message, isLoading: Boolean = false) {
    val isUser = message.role == MessageRole.USER
    
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(fraction = 0.75f)
                .widthIn(min = 200.dp, max = 800.dp)
        ) {
            Box(
                modifier = Modifier
                    .clip(
                        RoundedCornerShape(
                            topStart = 16.dp,
                            topEnd = 16.dp,
                            bottomStart = if (isUser) 16.dp else 4.dp,
                            bottomEnd = if (isUser) 4.dp else 16.dp
                        )
                    )
                    .background(
                        if (isUser) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
                    .padding(12.dp)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Text(
                        text = message.content,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isUser) 
                            MaterialTheme.colorScheme.onPrimary 
                        else 
                            MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            // Отображаем время ответа и информацию о токенах для сообщений от ассистента
            if (!isUser && !isLoading) {
                Column(
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp)
                ) {
                    if (message.responseTimeMs != null) {
                        Text(
                            text = formatResponseTime(message.responseTimeMs),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    
                    // Отображаем информацию о токенах, если доступна
                    if (message.promptTokens != null || message.completionTokens != null || message.totalTokens != null) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(top = 2.dp)
                        ) {
                            message.promptTokens?.let { tokens ->
                                Text(
                                    text = "Prompt: $tokens",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                            }
                            message.completionTokens?.let { tokens ->
                                Text(
                                    text = "Completion: $tokens",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                            }
                            message.totalTokens?.let { tokens ->
                                Text(
                                    text = "Total: $tokens",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatResponseTime(timeMs: Long): String {
    return when {
        timeMs < 1000 -> "${timeMs}ms"
        timeMs < 60000 -> String.format("%.1fs", timeMs / 1000.0)
        else -> String.format("%.1fmin", timeMs / 60000.0)
    }
}

@Composable
fun AccessTokenDialog(
    currentToken: String,
    isLoadingToken: Boolean,
    onDismiss: () -> Unit,
    onConfirmToken: (String) -> Unit,
    onRefreshToken: () -> Unit
) {
    var accessTokenText by remember { mutableStateOf(currentToken) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Access Token") },
        text = {
            Column {
                Text(
                    "Access Token is automatically obtained using built-in Authorization Key.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(16.dp))
                
                if (isLoadingToken) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Getting access token...")
                    }
                } else {
                    OutlinedTextField(
                        value = accessTokenText,
                        onValueChange = { accessTokenText = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Enter access token manually...") },
                        label = { Text("Access Token") },
                        singleLine = true,
                        enabled = !isLoadingToken
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = onRefreshToken,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isLoadingToken
                    ) {
                        Text("Refresh Token")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirmToken(accessTokenText) },
                enabled = accessTokenText.isNotBlank() && !isLoadingToken
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun TemperatureDialog(
    currentTemperature: Float,
    onDismiss: () -> Unit,
    onConfirm: (Float) -> Unit
) {
    var temperatureText by remember(currentTemperature) { mutableStateOf(currentTemperature.toString()) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    
    // Обновляем текст при изменении currentTemperature
    LaunchedEffect(currentTemperature) {
        temperatureText = currentTemperature.toString()
    }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Temperature") },
        text = {
            Column {
                Text(
                    "Temperature controls the randomness of the AI's responses. " +
                    "Lower values (0.0-0.5) make responses more focused and deterministic. " +
                    "Higher values (0.5-2.0) make responses more creative and diverse.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(16.dp))
                
                OutlinedTextField(
                    value = temperatureText,
                    onValueChange = { 
                        temperatureText = it
                        errorMessage = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("0.0") },
                    label = { Text("Temperature (0.0 - 2.0)") },
                    singleLine = true,
                    isError = errorMessage != null,
                    supportingText = errorMessage?.let { { Text(it) } }
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val value = temperatureText.toFloatOrNull()
                    if (value != null) {
                        if (value in 0.0f..2.0f) {
                            onConfirm(value)
                        } else {
                            errorMessage = "Temperature must be between 0.0 and 2.0"
                        }
                    } else {
                        errorMessage = "Please enter a valid number"
                    }
                }
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun ModelSelectionDialog(
    currentModel: AiModel,
    onDismiss: () -> Unit,
    onSelectModel: (AiModel) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select AI Model") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AiModel.ALL_MODELS.forEach { model ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = model.displayName,
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                text = when (model.type) {
                                    AiModelType.GIGACHAT -> "Sberbank GigaChat"
                                    AiModelType.HUGGINGFACE -> "HuggingFace Inference API"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        RadioButton(
                            selected = model == currentModel,
                            onClick = { onSelectModel(model) }
                        )
                    }
                    if (model != AiModel.ALL_MODELS.last()) {
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@Composable
fun HuggingFaceTokenDialog(
    currentToken: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var tokenText by remember(currentToken) { mutableStateOf(currentToken) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    
    LaunchedEffect(currentToken) {
        tokenText = currentToken
    }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("HuggingFace API Token") },
        text = {
            Column {
                Text(
                    "To use HuggingFace models, you need an API token. " +
                    "You can get one for free at https://huggingface.co/settings/tokens",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(16.dp))
                
                OutlinedTextField(
                    value = tokenText,
                    onValueChange = { 
                        tokenText = it
                        errorMessage = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Enter your HuggingFace API token...") },
                    label = { Text("API Token") },
                    singleLine = true,
                    isError = errorMessage != null,
                    supportingText = errorMessage?.let { { Text(it) } }
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (tokenText.isNotBlank()) {
                        onConfirm(tokenText.trim())
                    } else {
                        errorMessage = "Token cannot be empty"
                    }
                }
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

