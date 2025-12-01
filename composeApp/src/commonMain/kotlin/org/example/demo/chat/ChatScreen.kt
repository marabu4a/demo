package org.example.demo.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
    
    var messageText by remember { mutableStateOf("") }
    var showAccessTokenDialog by remember { mutableStateOf(false) }
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
    
    // Показ ошибок
    errorMessage?.let { error ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissError() },
            title = { Text("Error") },
            text = { Text(error) },
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
                .widthIn(max = 280.dp)
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

