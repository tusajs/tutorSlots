# app-kotlin — Telegram-бот TutorSlots

Kotlin-порт ядра + загрузка расписания из Google Sheets + картинка PNG + Telegram-бот.

## 1. Открыть в IntelliJ IDEA (или Android Studio)

1. **File → Open…** и выберите папку `app-kotlin`. Если открыт корень репозитория, нажмите правой кнопкой на `app-kotlin/build.gradle.kts` → **Link Gradle Project**.
2. Android Studio предложит Gradle-синхронизацию — соглашайтесь. При первом запуске она скачает Gradle 8.14.3 и библиотеки из Maven Central, это займёт несколько минут.
3. Если появится ошибка про JDK: **File → Settings → Build, Execution, Deployment → Build Tools → Gradle → Gradle JDK** и выберите **Temurin 17** или встроенный **JetBrains Runtime 21**. Подойдёт любой JDK 17+.
4. Тесты: правой кнопкой по `src/test/kotlin` → **Run 'Tests in…'**. Или в терминале:
   ```powershell
   .\gradlew.bat test
   ```

Android Studio может предложить «Android-специфичные» настройки или обновление плагина Android Gradle — их можно игнорировать: это обычный JVM-проект.

## 2. Подготовить ключи (один раз)

### Telegram-бот
1. В Telegram откройте **@BotFather** → `/newbot` → придумайте имя и username (должен заканчиваться на `bot`).
2. BotFather пришлёт токен вида `1234567890:AAH…` — это `BOT_TOKEN`.

### Ключ Google Sheets API
1. Откройте https://console.cloud.google.com/ и войдите Google-аккаунтом.
2. Создайте проект: вверху **Select a project → New project**, имя любое.
3. **APIs & Services → Library** → найдите **Google Sheets API** → **Enable**.
4. **APIs & Services → Credentials → Create credentials → API key**. Скопируйте ключ — это `GOOGLE_API_KEY`.
5. Рекомендуется: в настройках ключа **API restrictions → Restrict key → Google Sheets API**.

Оплата и банковская карта для этого не нужны: чтение открытой таблицы бесплатно.

### Таблица
В Google Таблице: **Настройки доступа → Все, у кого есть ссылка → Читатель**. Ссылку целиком вставьте в `SPREADSHEET`.

## 3. Настроить и запустить

```powershell
copy bot.env.example bot.env
notepad bot.env          # вписать BOT_TOKEN, GOOGLE_API_KEY, SPREADSHEET
.\gradlew.bat run --args="fetch"     # проверка: скачать расписание и вывести его
.\gradlew.bat run                    # запустить бота
```

В Android Studio: откройте `Main.kt` → зелёный треугольник у `fun main` → **Run**. Для режима `fetch` добавьте его в **Run → Edit Configurations → Program arguments**.

Затем напишите боту `/id`, впишите полученный номер в `TUTOR_ID` в `bot.env` и перезапустите — команда `/stats` станет доступна вам.

## 4. Режимы запуска

| Команда | Что делает |
|---|---|
| `bot` (по умолчанию) | Запускает бота (long polling — сервер и белый IP не нужны) |
| `fetch [--out file.tsv]` | Скачивает таблицу и выводит снимок — удобно проверить ключ и ссылку |
| `report <snapshot.tsv> [--offset +07:00] [--earliest 08:00]` | Текстовый отчёт, побайтно такой же, как у C++ `tutor_slots` |
| `render <snapshot.tsv> [--zone Новосибирск] [--out slots.png]` | Картинка без Telegram — для отладки и защиты |

Пример без ключей и сети:
```powershell
.\gradlew.bat run --args="render ..\samples\snapshot.tsv --zone Новосибирск --out slots.png"
```

## 5. Команды бота

| Команда | Кто | Что делает |
|---|---|---|
| `/start` | все | Приветствие и выбор пояса кнопками |
| `/slots`, кнопка «🗓 Свободные окна» | все | Картинка со свободными окнами в поясе пользователя |
| `/tz`, кнопка «🌍 Часовой пояс» | все | Смена пояса; «Другой…» — ввод города, `+5:30` или `Asia/Tokyo` |
| `/help` | все | Справка |
| `/id` | все | Показывает Telegram ID — нужен для `TUTOR_ID` |
| `/stats` | только репетитор | Загрузка недели, свободные и занятые часы |

## 6. Если Telegram или Google не открываются

Признак — в логе `Telegram connection error: HTTP connect timed out`. Значит, `api.telegram.org` недоступен из вашей сети напрямую.

1. **Включите VPN.** Бот сам использует системный прокси Windows (`java.net.useSystemProxies=true`), так что подойдёт VPN как в режиме TUN, так и в режиме «системный прокси».
2. **Проверьте доступность** командой `curl.exe -I -m 10 https://api.telegram.org`. Нужен ответ `HTTP/1.1 302`. Таймаут означает, что трафик идёт мимо VPN.
3. **Раздельное туннелирование** (например, в AmneziaVPN: Настройки → Соединение → Раздельное туннелирование сайтов): выключите его или добавьте `api.telegram.org`, `149.154.160.0/20`, `91.108.4.0/22`, `91.108.56.0/22`. Затем переподключите VPN.
4. Если VPN-клиент даёт только локальный прокси, укажите его **HTTP**-порт в `bot.env`: `PROXY=127.0.0.1:10809`. SOCKS не поддерживается встроенным HTTP-клиентом Java.

Если Google недоступен уже во время работы бота, клиенты получат последнюю сохранённую версию расписания с пометкой о дате (`data/snapshot.tsv`).

## 7. Быстрый запуск без IDE

Двойной щелчок по `start-bot.bat` (сначала включите VPN). Остановить — закрыть окно или Ctrl+C.

## 8. Структура

```
src/main/kotlin/tutorslots/
├── Main.kt                     режимы запуска
├── core/Core.kt                порт ядра C++ (тот же алгоритм и формат отчёта)
├── data/                       Config, SheetsClient (+SheetsParser), ScheduleRepository, UserSettingsStore
├── domain/                     ZoneResolver, SlotsService, SlotsView
└── presentation/               SlotsRenderer (PNG), TelegramApi (HTTP), TutorBot (диалог)
src/test/kotlin/…               71 тест JUnit 5
src/test/resources/             пример ответа Google Sheets API
```
