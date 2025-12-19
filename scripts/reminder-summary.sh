#!/bin/bash

# Скрипт для запуска cron-агента полной сводки
# Использование в cron: 0 */6 * * * /path/to/this/script.sh

# Переходим в директорию проекта
cd "$(dirname "$0")/.." || exit 1

# Запускаем cron-агента
./gradlew :server:runCronSummary >> ~/reminder-summary.log 2>&1


