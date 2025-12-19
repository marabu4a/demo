# Cron-агент для напоминаний

## Описание

Cron-агент (`CronReminderAgent`) - это альтернативный подход к реализации агента напоминаний. Вместо долгоживущего процесса, который работает 24/7, cron-агент запускается по расписанию через cron и выполняет одну проверку, после чего завершает работу.

## Преимущества cron-подхода

✅ **Простота** - не нужно поддерживать долгоживущий процесс  
✅ **Надежность** - если процесс упадет, cron перезапустит его  
✅ **Гибкость** - можно настроить разные расписания для разных задач  
✅ **Ресурсы** - не занимает память между запусками  
✅ **Логирование** - каждый запуск логируется отдельно  

## Недостатки

❌ **Задержка** - проверка происходит только по расписанию, не в реальном времени  
❌ **Настройка cron** - требует настройки cron на системе  
❌ **Нет состояния** - не помнит время последней сводки между запусками  

## Архитектура

```
Cron (расписание)
    ↓
Запуск CronReminderAgent
    ↓
Выполнение одной проверки
    ↓
Отправка уведомления
    ↓
Завершение работы
```

## Использование

### Прямой запуск

```bash
# Проверка просроченных напоминаний
./gradlew :server:runCronCheckDue

# Полная сводка
./gradlew :server:runCronSummary
```

### Через командную строку

```bash
# После сборки
./gradlew :server:build

# Проверка просроченных
java -cp server/build/libs/server-1.0.0.jar \
  org.example.demo.CronReminderAgentKt check_due

# Полная сводка
java -cp server/build/libs/server-1.0.0.jar \
  org.example.demo.CronReminderAgentKt summary
```

## Настройка cron на macOS

### 1. Создайте скрипт-обертку

Создайте файл `~/bin/reminder-check.sh`:

```bash
#!/bin/bash

# Переходим в директорию проекта
cd /Users/trubeev.gleb/IdeaProjects/demo

# Запускаем cron-агента
./gradlew :server:runCronCheckDue >> ~/reminder-cron.log 2>&1
```

Сделайте его исполняемым:
```bash
chmod +x ~/bin/reminder-check.sh
```

### 2. Настройте cron

Откройте crontab:
```bash
crontab -e
```

Добавьте строки:

```cron
# Проверка просроченных напоминаний каждый час
0 * * * * /Users/trubeev.gleb/bin/reminder-check.sh

# Полная сводка каждые 6 часов
0 */6 * * * cd /Users/trubeev.gleb/IdeaProjects/demo && ./gradlew :server:runCronSummary >> ~/reminder-summary.log 2>&1
```

### 3. Альтернатива: launchd (рекомендуется для macOS)

Создайте файл `~/Library/LaunchAgents/com.demo.reminder-check.plist`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>Label</key>
    <string>com.demo.reminder-check</string>
    <key>ProgramArguments</key>
    <array>
        <string>/Users/trubeev.gleb/IdeaProjects/demo/gradlew</string>
        <string>:server:runCronCheckDue</string>
    </array>
    <key>WorkingDirectory</key>
    <string>/Users/trubeev.gleb/IdeaProjects/demo</string>
    <key>StartInterval</key>
    <integer>3600</integer>
    <key>StandardOutPath</key>
    <string>/Users/trubeev.gleb/reminder-check.log</string>
    <key>StandardErrorPath</key>
    <string>/Users/trubeev.gleb/reminder-check-error.log</string>
</dict>
</plist>
```

Загрузите задачу:
```bash
launchctl load ~/Library/LaunchAgents/com.demo.reminder-check.plist
```

Запустите:
```bash
launchctl start com.demo.reminder-check
```

Проверьте статус:
```bash
launchctl list | grep reminder
```

## Примеры расписаний cron

### Каждый час (проверка просроченных)
```cron
0 * * * * /path/to/script.sh
```

### Каждые 6 часов (полная сводка)
```cron
0 */6 * * * /path/to/script.sh
```

### Каждые 30 минут
```cron
*/30 * * * * /path/to/script.sh
```

### Каждый день в 9:00
```cron
0 9 * * * /path/to/script.sh
```

### Каждый понедельник в 10:00
```cron
0 10 * * 1 /path/to/script.sh
```

## Сравнение подходов

| Характеристика | Долгоживущий процесс | Cron-агент |
|----------------|---------------------|------------|
| Память | Постоянно занимает | Освобождает после работы |
| Задержка | Минимальная | Зависит от расписания |
| Настройка | Простая (один запуск) | Требует настройки cron |
| Надежность | Нужен мониторинг | Cron перезапускает при сбое |
| Логирование | Один большой лог | Отдельный лог на запуск |
| Гибкость | Фиксированные интервалы | Любое расписание |

## Рекомендации

**Используйте долгоживущий процесс (AiReminderAgent), если:**
- Нужна минимальная задержка уведомлений
- Хотите простую настройку (один запуск)
- Не хотите настраивать cron

**Используйте cron-агент (CronReminderAgent), если:**
- Хотите более гибкое расписание
- Предпочитаете подход "запустил и забыл"
- Нужна лучшая изоляция между запусками
- Хотите отдельные логи для каждого запуска

## Тестирование cron-агента

```bash
# Тест проверки просроченных
./gradlew :server:runCronCheckDue

# Тест полной сводки
./gradlew :server:runCronSummary
```

## Результат

✅ У вас есть два варианта реализации агента:
1. **Долгоживущий процесс** - работает 24/7, проверяет периодически
2. **Cron-агент** - запускается по расписанию, выполняет одну проверку

Выбирайте подход в зависимости от ваших потребностей!


