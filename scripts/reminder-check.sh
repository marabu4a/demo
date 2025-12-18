#!/bin/bash

# Скрипт для запуска cron-агента проверки просроченных напоминаний
# Использование в cron: 0 * * * * /path/to/this/script.sh

# Переходим в директорию проекта
cd "$(dirname "$0")/.." || exit 1

# Запускаем cron-агента
./gradlew :server:runCronCheckDue >> ~/reminder-check.log 2>&1

