# Локальный MCP Сервер

## Описание

Простой локальный MCP (Model Context Protocol) сервер для тестирования подключения. Сервер реализует базовые методы MCP протокола и предоставляет несколько инструментов для демонстрации.

## Запуск сервера

### Способ 1: Через Gradle

```bash
./gradlew :server:run
```

### Способ 2: Через IntelliJ IDEA

1. Откройте файл `server/src/main/kotlin/org/example/demo/McpServer.kt`
2. Найдите функцию `main()`
3. Правой кнопкой мыши → **Run 'McpServerKt.main()'**

### Способ 3: Сборка и запуск JAR

```bash
./gradlew :server:build
java -jar server/build/libs/server-1.0.0.jar
```

## URL сервера

После запуска сервер будет доступен по адресу:

- **Основной endpoint**: `http://localhost:8080/mcp`
- **Альтернативные endpoints**:
  - `http://localhost:8080/mcp/message`
  - `http://localhost:8080/message`
- **Health check**: `http://localhost:8080/health`
- **Информация**: `http://localhost:8080/`

## Подключение из приложения

### В UI приложения:

1. Откройте диалог управления MCP серверами (кнопка "MCP" в TopAppBar)
2. Нажмите "+ Добавить сервер"
3. Введите:
   - **Имя**: `Local MCP Server`
   - **URL**: `http://localhost:8080/mcp`
4. Нажмите "Добавить и подключить"

### Программно:

```kotlin
val manager = McpClientManager(httpClient)
manager.connectServer("local-server", "http://localhost:8080/mcp")
```

### В тестовом файле:

В `McpTest.kt` измените URL:

```kotlin
val serverUrl = "http://localhost:8080/mcp"
```

## Доступные инструменты

Сервер предоставляет следующие инструменты:

### 1. `echo`
Эхо-инструмент, который возвращает введенный текст.

**Параметры:**
- `text` (string) - Текст для эха

**Пример:**
```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "tools/call",
  "params": {
    "name": "echo",
    "arguments": {
      "text": "Hello, MCP!"
    }
  }
}
```

### 2. `get_current_time`
Возвращает текущее время сервера.

**Параметры:** Нет

**Пример:**
```json
{
  "jsonrpc": "2.0",
  "id": 2,
  "method": "tools/call",
  "params": {
    "name": "get_current_time"
  }
}
```

### 3. `calculate`
Выполняет простые арифметические операции.

**Параметры:**
- `expression` (string) - Математическое выражение (например, "2 + 2")

**Пример:**
```json
{
  "jsonrpc": "2.0",
  "id": 3,
  "method": "tools/call",
  "params": {
    "name": "calculate",
    "arguments": {
      "expression": "10 + 5 * 2"
    }
  }
}
```

## Доступные ресурсы

Сервер предоставляет следующие ресурсы:

### 1. `file:///example.txt`
Пример текстового ресурса.

### 2. `file:///server-info.json`
Информация о сервере в формате JSON.

## Тестирование

### Через curl:

```bash
# Health check
curl http://localhost:8080/health

# Initialize
curl -X POST http://localhost:8080/mcp \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "initialize",
    "params": {
      "protocolVersion": "2024-11-05",
      "capabilities": {},
      "clientInfo": {
        "name": "test-client",
        "version": "1.0.0"
      }
    }
  }'

# List tools
curl -X POST http://localhost:8080/mcp \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 2,
    "method": "tools/list"
  }'

# Call tool
curl -X POST http://localhost:8080/mcp \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 3,
    "method": "tools/call",
    "params": {
      "name": "echo",
      "arguments": {
        "text": "Hello from curl!"
      }
    }
  }'
```

### Через приложение:

Запустите `McpTest.kt` с URL `http://localhost:8080/mcp`

## Структура проекта

- `server/src/main/kotlin/org/example/demo/McpServer.kt` - Основной файл сервера
- `server/build.gradle.kts` - Конфигурация сборки

## Расширение функциональности

Чтобы добавить новые инструменты:

1. Добавьте инструмент в `handleToolsList()`:
```kotlin
addJsonObject {
    put("name", "my_tool")
    put("description", "My custom tool")
    // ... inputSchema
}
```

2. Обработайте вызов в `handleToolCall()`:
```kotlin
"my_tool" -> {
    // Ваша логика
    buildJsonObject {
        putJsonArray("content") {
            addJsonObject {
                put("type", "text")
                put("text", "Result")
            }
        }
        put("isError", false)
    }
}
```

## Примечания

- Сервер использует порт **8080** по умолчанию
- Для изменения порта отредактируйте `embeddedServer(Netty, port = 8080, ...)` в `McpServer.kt`
- Калькулятор (`calculate`) использует упрощенную реализацию - в продакшене используйте безопасный парсер выражений
- Сервер поддерживает CORS для работы из браузера

## Устранение проблем

### Сервер не запускается

- Проверьте, что порт 8080 свободен
- Убедитесь, что все зависимости установлены: `./gradlew :server:build`

### Не удается подключиться из приложения

- Убедитесь, что сервер запущен
- Проверьте URL: должен быть `http://localhost:8080/mcp`
- Проверьте логи сервера в консоли

### Ошибки компиляции

- Убедитесь, что добавлены все зависимости в `build.gradle.kts`
- Выполните `./gradlew clean build`


