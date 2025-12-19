# Тестирование MCP клиента через POST запросы

Существует несколько способов протестировать MCP сервер через POST запросы:

## 1. IntelliJ IDEA HTTP Client (Рекомендуется)

IntelliJ IDEA имеет встроенный HTTP Client, который очень удобен для тестирования.

### Использование:

1. Откройте файл `test-mcp-requests.http` в IntelliJ IDEA
2. Убедитесь, что MCP сервер запущен (`./gradlew :server:run`)
3. Нажмите кнопку ▶️ рядом с нужным запросом или используйте `Ctrl+Enter` (Windows/Linux) / `Cmd+Enter` (Mac)

### Преимущества:
- ✅ Встроен в IDE
- ✅ Подсветка синтаксиса
- ✅ Переменные окружения
- ✅ История запросов
- ✅ Автодополнение

## 2. curl (Командная строка)

`curl` - самый популярный инструмент для работы с HTTP запросами из командной строки.

### Базовый пример:

```bash
curl -X POST http://localhost:8080/mcp \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "tools/list"
  }'
```

### С форматированием JSON (требуется `jq`):

```bash
curl -X POST http://localhost:8080/mcp \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "tools/list"
  }' | jq '.'
```

### Готовый скрипт:

Используйте файл `test-mcp-curl.sh`:

```bash
chmod +x test-mcp-curl.sh
./test-mcp-curl.sh
```

## 3. httpie (Более удобная альтернатива curl)

`httpie` - более современный и удобный инструмент для HTTP запросов.

### Установка:

```bash
# macOS
brew install httpie

# Linux
sudo apt install httpie

# Windows
pip install httpie
```

### Пример использования:

```bash
http POST http://localhost:8080/mcp \
  jsonrpc=2.0 \
  id:=1 \
  method=tools/list
```

Или с JSON файлом:

```bash
http POST http://localhost:8080/mcp < request.json
```

## 4. Postman (GUI приложение)

Postman - популярное GUI приложение для тестирования API.

### Настройка:

1. Создайте новый запрос
2. Метод: **POST**
3. URL: `http://localhost:8080/mcp`
4. Headers:
   - `Content-Type: application/json`
5. Body (raw JSON):
```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "tools/list"
}
```

### Преимущества:
- ✅ Удобный GUI
- ✅ Коллекции запросов
- ✅ Переменные окружения
- ✅ Автоматизация тестов
- ✅ Документация API

## 5. VS Code REST Client

Расширение для VS Code, аналогичное IntelliJ HTTP Client.

### Установка:

1. Установите расширение "REST Client" в VS Code
2. Откройте файл `test-mcp-requests.http`
3. Нажмите "Send Request" над запросом

## Примеры запросов

### 1. Получить список инструментов

```bash
curl -X POST http://localhost:8080/mcp \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "tools/list"
  }'
```

### 2. Вызвать инструмент yandex_tracker

```bash
curl -X POST http://localhost:8080/mcp \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 2,
    "method": "tools/call",
    "params": {
      "name": "yandex_tracker",
      "arguments": {
        "action": "count_open_tasks",
        "queue": "TEST"
      }
    }
  }'
```

### 3. Вызвать инструмент echo

```bash
curl -X POST http://localhost:8080/mcp \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 3,
    "method": "tools/call",
    "params": {
      "name": "echo",
      "arguments": {
        "text": "Hello, MCP!"
      }
    }
  }'
```

### 4. Получить текущее время

```bash
curl -X POST http://localhost:8080/mcp \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 4,
    "method": "tools/call",
    "params": {
      "name": "get_current_time"
    }
  }'
```

### 5. Вычислить выражение

```bash
curl -X POST http://localhost:8080/mcp \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 5,
    "method": "tools/call",
    "params": {
      "name": "calculate",
      "arguments": {
        "expression": "2 + 2 * 3"
      }
    }
  }'
```

## Проверка ответов

### Успешный ответ:

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": {
    "tools": [
      {
        "name": "yandex_tracker",
        "description": "Работа с задачами Яндекс Трекера...",
        ...
      }
    ]
  }
}
```

### Ответ с ошибкой:

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "error": {
    "code": -32601,
    "message": "Method not found: invalid_method"
  }
}
```

## Отладка

### Проверка доступности сервера:

```bash
curl http://localhost:8080/health
```

### Проверка с подробным выводом:

```bash
curl -v -X POST http://localhost:8080/mcp \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
```

Флаг `-v` (verbose) покажет заголовки запроса и ответа.

### Сохранение ответа в файл:

```bash
curl -X POST http://localhost:8080/mcp \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}' \
  -o response.json
```

## Рекомендации

1. **Для быстрого тестирования**: Используйте IntelliJ IDEA HTTP Client или VS Code REST Client
2. **Для автоматизации**: Используйте curl в скриптах
3. **Для документации**: Используйте Postman для создания коллекций
4. **Для разработки**: Используйте готовый файл `test-mcp-requests.http`

## Файлы в проекте

- `test-mcp-requests.http` - Готовые запросы для IntelliJ IDEA / VS Code
- `test-mcp-curl.sh` - Bash скрипт с примерами curl запросов

## Быстрый старт

1. Запустите MCP сервер:
   ```bash
   ./gradlew :server:run
   ```

2. В другом терминале запустите тесты:
   ```bash
   # Через скрипт
   ./test-mcp-curl.sh
   
   # Или вручную
   curl -X POST http://localhost:8080/mcp \
     -H "Content-Type: application/json" \
     -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}' | jq '.'
   ```

3. Или откройте `test-mcp-requests.http` в IntelliJ IDEA и запустите запросы оттуда.


