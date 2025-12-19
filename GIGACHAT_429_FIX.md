# Исправление ошибки 429 (Too Many Requests) в GigaChat

## Проблема

При отправке запросов к GigaChat API сразу же возникает ошибка **429 Too Many Requests**, что означает превышение лимита запросов.

## Возможные причины

1. **Слишком частые запросы на получение токена** - каждый раз при отправке сообщения может запрашиваться новый токен
2. **Отсутствие задержек между запросами** - запросы отправляются без пауз
3. **Нет retry логики** - при ошибке 429 запрос не повторяется с задержкой
4. **Превышение лимита запросов в секунду/минуту** - GigaChat имеет ограничения на частоту запросов

## Решения

### 1. Добавить retry логику с экспоненциальной задержкой

Добавьте обработку ошибки 429 с повторными попытками:

```kotlin
private suspend fun sendMessageWithRetry(
    messages: List<Message>, 
    temperature: Float, 
    maxTokens: Int?,
    maxRetries: Int = 3
): String {
    var lastException: Exception? = null
    
    for (attempt in 1..maxRetries) {
        try {
            return sendMessageInternal(messages, temperature, maxTokens)
        } catch (e: Exception) {
            lastException = e
            if (e.message?.contains("429") == true || e.message?.contains("Too Many Requests") == true) {
                if (attempt < maxRetries) {
                    // Экспоненциальная задержка: 2^attempt секунд
                    val delaySeconds = (1 shl attempt).toLong()
                    AppLogger.w(TAG, "Rate limit hit (429), retrying in ${delaySeconds}s (attempt $attempt/$maxRetries)")
                    delay(delaySeconds * 1000)
                    continue
                }
            }
            throw e
        }
    }
    
    throw lastException ?: Exception("Failed after $maxRetries attempts")
}
```

### 2. Кэшировать токен и не запрашивать его слишком часто

Убедитесь, что токен запрашивается только при необходимости:

```kotlin
private suspend fun refreshTokenIfNeeded() {
    if (accessToken.isBlank() || isTokenExpired()) {
        AppLogger.d(TAG, "Token expired or missing, refreshing...")
        val newToken = getAccessToken()
        setAccessToken(token, expiresAt)
    }
}
```

### 3. Добавить задержку между запросами

Добавьте минимальную задержку между запросами:

```kotlin
private var lastRequestTime: Long = 0
private const val MIN_REQUEST_INTERVAL_MS = 1000L // 1 секунда между запросами

private suspend fun waitIfNeeded() {
    val now = currentTimeMillis()
    val timeSinceLastRequest = now - lastRequestTime
    if (timeSinceLastRequest < MIN_REQUEST_INTERVAL_MS) {
        val delay = MIN_REQUEST_INTERVAL_MS - timeSinceLastRequest
        AppLogger.d(TAG, "Rate limiting: waiting ${delay}ms before next request")
        delay(delay)
    }
    lastRequestTime = currentTimeMillis()
}
```

### 4. Проверять заголовки ответа на rate limit

GigaChat может возвращать информацию о лимитах в заголовках:

```kotlin
val retryAfter = httpResponse.headers["Retry-After"]?.toLongOrNull()
if (retryAfter != null) {
    AppLogger.w(TAG, "Rate limit: waiting ${retryAfter}s as specified by Retry-After header")
    delay(retryAfter * 1000)
}
```

## Рекомендуемые изменения

1. **Добавить retry логику** для ошибок 429
2. **Кэшировать токен** и не запрашивать его при каждом запросе
3. **Добавить минимальную задержку** между запросами (1-2 секунды)
4. **Обрабатывать заголовок Retry-After** из ответа
5. **Логировать информацию о rate limits** для отладки

## Проверка лимитов GigaChat

GigaChat имеет следующие лимиты (могут изменяться):
- **Запросы в минуту**: зависит от тарифа
- **Запросы в секунду**: обычно 1-2 запроса/сек
- **Токены в минуту**: зависит от модели

## Временное решение

Если нужно быстро решить проблему, добавьте задержку перед каждым запросом:

```kotlin
override suspend fun sendMessage(...): String = withContext(Dispatchers.Default) {
    // Добавьте задержку перед запросом
    delay(2000) // 2 секунды задержки
    
    // Остальной код...
}
```

## Долгосрочное решение

Реализуйте полноценную систему rate limiting с:
- Retry логикой
- Экспоненциальной задержкой
- Кэшированием токенов
- Обработкой заголовков Retry-After
- Мониторингом лимитов


