package org.example.demo.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
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
    val maxTokens by viewModel.maxTokens
    val selectedModel by viewModel.selectedModel
    val huggingFaceToken by viewModel.huggingFaceToken
    val dialogueMode by viewModel.dialogueMode
    
    var messageText by remember { mutableStateOf("") }
    var showAccessTokenDialog by remember { mutableStateOf(false) }
    var showTemperatureDialog by remember { mutableStateOf(false) }
    var showMaxTokensDialog by remember { mutableStateOf(false) }
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
                // Показываем кнопку maxTokens только для HuggingFace моделей
                if (selectedModel.type == AiModelType.HUGGINGFACE) {
                    IconButton(
                        onClick = { showMaxTokensDialog = true },
                        modifier = Modifier.padding(horizontal = 4.dp)
                    ) {
                        Text(
                            text = maxTokens?.let { "Max: $it" } ?: "Max: ∞",
                            fontSize = 12.sp,
                            color = if (maxTokens != null) 
                                MaterialTheme.colorScheme.primary 
                            else 
                                MaterialTheme.colorScheme.onSurface
                        )
                    }
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
                IconButton(
                    onClick = { viewModel.toggleDialogueMode() },
                    modifier = Modifier.padding(horizontal = 4.dp)
                ) {
                    Text(
                        text = if (dialogueMode == DialogueMode.SUMMARY) "Summary" else "Normal",
                        fontSize = 12.sp,
                        color = if (dialogueMode == DialogueMode.SUMMARY) 
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
            
            items(session.messages.size) { index ->
                val message = session.messages[index]
                // Находим предыдущее сообщение от ассистента для анализа изменений токенов
                val previousAssistantMessage = session.messages
                    .take(index)
                    .lastOrNull { it.role == MessageRole.ASSISTANT && it.totalTokens != null }
                MessageBubble(
                    message = message,
                    previousMessage = previousAssistantMessage
                )
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
    
    // Диалог для настройки Max Tokens (только для HuggingFace)
    if (showMaxTokensDialog) {
        MaxTokensDialog(
            currentMaxTokens = maxTokens,
            onDismiss = { showMaxTokensDialog = false },
            onConfirm = { value ->
                viewModel.setMaxTokens(value)
                showMaxTokensDialog = false
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
fun MessageBubble(message: Message, isLoading: Boolean = false, previousMessage: Message? = null) {
    val isUser = message.role == MessageRole.USER
    val isSummary = message.role == MessageRole.SYSTEM && message.content.startsWith("[Summary]")
    val summaryContent = if (isSummary) {
        message.content.removePrefix("[Summary]").trim()
    } else {
        message.content
    }
    
    val clipboardManager = LocalClipboardManager.current
    var showCopyConfirmation by remember { mutableStateOf(false) }
    
    // Показываем подтверждение копирования на 2 секунды
    LaunchedEffect(showCopyConfirmation) {
        if (showCopyConfirmation) {
            kotlinx.coroutines.delay(2000)
            showCopyConfirmation = false
        }
    }
    
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(fraction = 0.75f)
                .widthIn(min = 200.dp, max = 800.dp)
        ) {
            // Показываем метку для summary-сообщений
            if (isSummary) {
                Text(
                    text = "📋 Summary",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 4.dp, start = 4.dp)
                )
            }
            
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
                        when {
                            isUser -> MaterialTheme.colorScheme.primary
                            isSummary -> MaterialTheme.colorScheme.secondaryContainer
                            else -> MaterialTheme.colorScheme.surfaceVariant
                        }
                    )
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = summaryContent,
                        style = MaterialTheme.typography.bodyMedium,
                        color = when {
                            isUser -> MaterialTheme.colorScheme.onPrimary
                            isSummary -> MaterialTheme.colorScheme.onSecondaryContainer
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier
                            .weight(1f)
                            .padding(12.dp)
                    )
                    
                    if (!isLoading) {
                        Text(
                            text = if (showCopyConfirmation) "✓" else "📋",
                            style = MaterialTheme.typography.bodySmall,
                            color = when {
                                isUser -> MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
                                isSummary -> MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                                else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            },
                            modifier = Modifier
                                .padding(end = 8.dp)
                                .clickable(
                                    onClick = {
                                        clipboardManager.setText(AnnotatedString(summaryContent))
                                        showCopyConfirmation = true
                                    }
                                )
                        )
                    } else {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .size(20.dp)
                                .padding(12.dp),
                            strokeWidth = 2.dp
                        )
                    }
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
                    
                    // Отображаем общее количество отправленных сообщений
                    message.sentMessagesCount?.let { count ->
                        Text(
                            text = "Сообщений в запросе: $count",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                    
                    // Отображаем информацию о токенах с анализом изменений для HuggingFace
                    if (message.promptTokens != null || message.completionTokens != null || message.totalTokens != null) {
                        Column(
                            modifier = Modifier.padding(top = 2.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            // Основная информация о токенах
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
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
                            
                            // Анализ изменений по сравнению с предыдущим сообщением
                            previousMessage?.let { prev ->
                                val changes = buildList {
                                    message.promptTokens?.let { current ->
                                        prev.promptTokens?.let { prevValue ->
                                            val diff = current - prevValue
                                            if (diff != 0) {
                                                add("Prompt: ${formatTokenChange(diff)}")
                                            }
                                        }
                                    }
                                    message.completionTokens?.let { current ->
                                        prev.completionTokens?.let { prevValue ->
                                            val diff = current - prevValue
                                            if (diff != 0) {
                                                add("Completion: ${formatTokenChange(diff)}")
                                            }
                                        }
                                    }
                                    message.totalTokens?.let { current ->
                                        prev.totalTokens?.let { prevValue ->
                                            val diff = current - prevValue
                                            if (diff != 0) {
                                                add("Total: ${formatTokenChange(diff)}")
                                            }
                                        }
                                    }
                                }
                                
                                if (changes.isNotEmpty()) {
                                    Text(
                                        text = "Изменение: ${changes.joinToString(", ")}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                                        modifier = Modifier.padding(top = 2.dp)
                                    )
                                }
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

private fun formatTokenChange(diff: Int): String {
    return when {
        diff > 0 -> "+$diff"
        diff < 0 -> "$diff"
        else -> "0"
    }
}

@Composable
fun MaxTokensDialog(
    currentMaxTokens: Int?,
    onDismiss: () -> Unit,
    onConfirm: (Int?) -> Unit
) {
    var maxTokensText by remember(currentMaxTokens) { mutableStateOf(currentMaxTokens?.toString() ?: "") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    
    LaunchedEffect(currentMaxTokens) {
        maxTokensText = currentMaxTokens?.toString() ?: ""
    }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Max Tokens") },
        text = {
            Column {
                Text(
                    "Maximum number of tokens to generate in the response. " +
                    "Leave empty for unlimited tokens. " +
                    "Typical values: 100-2000 tokens.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(16.dp))
                
                OutlinedTextField(
                    value = maxTokensText,
                    onValueChange = { 
                        maxTokensText = it
                        errorMessage = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Unlimited (leave empty)") },
                    label = { Text("Max Tokens") },
                    singleLine = true,
                    isError = errorMessage != null,
                    supportingText = errorMessage?.let { { Text(it) } }
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (maxTokensText.isBlank()) {
                        onConfirm(null)
                    } else {
                        val value = maxTokensText.toIntOrNull()
                        if (value != null && value > 0) {
                            onConfirm(value)
                        } else {
                            errorMessage = "Please enter a valid positive number"
                        }
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

