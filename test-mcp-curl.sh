#!/bin/bash

# Скрипт для тестирования MCP сервера через curl
# Использование: ./test-mcp-curl.sh

MCP_URL="http://localhost:8080/mcp"

echo "=========================================="
echo "Тестирование MCP сервера через curl"
echo "URL: $MCP_URL"
echo "=========================================="
echo ""

# Цвета для вывода
GREEN='\033[0;32m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# 1. Проверка доступности сервера
echo -e "${BLUE}1. Проверка доступности сервера (GET /health)${NC}"
curl -s http://localhost:8080/health | jq '.' || echo "Сервер не отвечает"
echo ""
echo ""

# 2. Инициализация
echo -e "${BLUE}2. Инициализация MCP соединения${NC}"
curl -s -X POST "$MCP_URL" \
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
  }' | jq '.'
echo ""
echo ""

# 3. Получить список инструментов
echo -e "${BLUE}3. Получение списка доступных инструментов${NC}"
curl -s -X POST "$MCP_URL" \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 2,
    "method": "tools/list"
  }' | jq '.'
echo ""
echo ""

# 4. Вызвать инструмент echo
echo -e "${BLUE}4. Вызов инструмента echo${NC}"
curl -s -X POST "$MCP_URL" \
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
  }' | jq '.'
echo ""
echo ""

# 5. Вызвать инструмент get_current_time
echo -e "${BLUE}5. Вызов инструмента get_current_time${NC}"
curl -s -X POST "$MCP_URL" \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 4,
    "method": "tools/call",
    "params": {
      "name": "get_current_time"
    }
  }' | jq '.'
echo ""
echo ""

# 6. Вызвать инструмент calculate
echo -e "${BLUE}6. Вызов инструмента calculate${NC}"
curl -s -X POST "$MCP_URL" \
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
  }' | jq '.'
echo ""
echo ""

# 7. Вызвать инструмент yandex_tracker - подсчет задач
echo -e "${BLUE}7. Вызов инструмента yandex_tracker (count_open_tasks)${NC}"
curl -s -X POST "$MCP_URL" \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 6,
    "method": "tools/call",
    "params": {
      "name": "yandex_tracker",
      "arguments": {
        "action": "count_open_tasks",
        "queue": "TEST"
      }
    }
  }' | jq '.'
echo ""
echo ""

# 8. Вызвать инструмент yandex_tracker - список задач
echo -e "${BLUE}8. Вызов инструмента yandex_tracker (get_open_tasks)${NC}"
curl -s -X POST "$MCP_URL" \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 7,
    "method": "tools/call",
    "params": {
      "name": "yandex_tracker",
      "arguments": {
        "action": "get_open_tasks",
        "queue": "TEST"
      }
    }
  }' | jq '.'
echo ""
echo ""

# 9. Вызвать инструмент yandex_tracker - информация о задаче
echo -e "${BLUE}9. Вызов инструмента yandex_tracker (get_task)${NC}"
curl -s -X POST "$MCP_URL" \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 8,
    "method": "tools/call",
    "params": {
      "name": "yandex_tracker",
      "arguments": {
        "action": "get_task",
        "task_id": "TEST-1",
        "queue": "TEST"
      }
    }
  }' | jq '.'
echo ""
echo ""

# 10. Получить список ресурсов
echo -e "${BLUE}10. Получение списка ресурсов${NC}"
curl -s -X POST "$MCP_URL" \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 9,
    "method": "resources/list"
  }' | jq '.'
echo ""
echo ""

echo -e "${GREEN}=========================================="
echo "Тестирование завершено!"
echo "==========================================${NC}"


