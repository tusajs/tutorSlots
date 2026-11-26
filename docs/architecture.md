# Архитектура TutorSlots

Этап 2 · проектирование. Диаграммы лежат в `docs/uml/`:

| Файл | Что показывает |
|---|---|
| `use-case.puml` | Акторы (клиент, репетитор, Google Sheets) и сценарии использования |
| `class-core.puml` | Классы ядра — одинаковые в C++ и Kotlin |
| `class-bot.puml` | Kotlin-приложение: данные, домен, бот, рендер |
| `sequence-slots.puml` | Обработка команды `/slots`, включая кэш и сбой Google API |

## 1. Модули

```
┌──────────────────────── app-kotlin ─────────────────────────┐
│  presentation:  TutorBot ───► SlotsRenderer (PNG, Java2D)   │
│                    │                                        │
│  domain:        SlotsService ─► ZoneResolver (java.time)    │
│                    │                                        │
│  data:   ScheduleRepository ─► SheetsClient ─► Sheets API   │
│          UserSettingsStore (users.json)    snapshot.tsv     │
│                    │                                        │
│  core:   порт ядра C++ (те же классы и тот же отчёт)        │
└─────────────────────────────────────────────────────────────┘
                     ▲ один и тот же snapshot.tsv
┌──────────────────────── core-cpp ───────────────────────────┐
│  SnapshotReader → CellClassifier → IntervalBuilder          │
│               → StatsCalculator → ReportWriter              │
│  CLI: tutor_slots <snapshot.tsv> --offset +04:00            │
│       [--earliest 08:00] [--out f]                          │
└─────────────────────────────────────────────────────────────┘
```

**Почему так.** Сеть, JSON и Telegram на C++ потребовали бы сторонних библиотек (libcurl, nlohmann/json) и сложной сборки под Windows, а для оценки ядра ничего бы не дали. Поэтому:
- ядро на C++ чистое: только стандартная библиотека, вход — файл, выход — отчёт;
- Kotlin отвечает за ввод-вывод с внешним миром и повторяет ядро один в один;
- общая точка — файл `snapshot.tsv`. По нему тесты обеих реализаций сверяются друг с другом (NFR-07, AC-23).

## 2. Ядро: поток данных

1. **SnapshotReader** читает TSV построчно. Пропускает пустые строки, комментарии `#` и заголовок. Проверяет поля и копит `Diagnostic` с номером строки (FR-21, FR-22).
2. **CellClassifier** определяет статус ячейки по правилам FR-05. Цвет классифицируется по HSV (FR-06):
   - белый — все каналы ≥ 240;
   - насыщенность `(max − min) / max` < 15 % — `OTHER` (серый, чёрный);
   - оттенок 340°…20° — красный, 75°…165° — зелёный, иначе — `OTHER`.

   Первая версия сравнивала «доминирующий канал» с порогом 40 и не распознавала бледно-зелёный `#B7E1CD` из палитры Google — дефект D-01, найден тестом AC-07.
3. **TimeShift**: `weekMinute = day·1440 + startMinute`, `client = ((weekMinute − 180 + offset) mod 10080 + 10080) mod 10080`. Второй `mod` нужен потому, что в C++ остаток от деления отрицательного числа отрицательный.
4. **IntervalBuilder** берёт свободные слоты, переводит во время клиента, отбрасывает начинающиеся раньше порога `earliestStart` (по умолчанию 08:00, FR-24), сортирует и склеивает соседние (`конец == начало следующего`) в пределах одних суток клиента. Слот, который пересекает полночь клиента (при смещении с :30 или :45 и времени 23:30), делится на две части (FR-10).
5. **StatsCalculator**: `free`, `busy`, `load = round(busy·100 / (free+busy))`. Округление целочисленное: `(busy·100 + total/2) / total`, чтобы C++ и Kotlin округляли одинаково.
6. **ReportWriter** пишет отчёт в фиксированном формате (раздел 3).

Предупреждения (`Diagnostic`) идут в `stderr` или в лог, а не в отчёт. Тогда отчёт зависит только от данных, и его можно сравнивать побайтно.

## 3. Формат текстового отчёта

ASCII, перевод строки `\n`, без завершающих пробелов. Дни всегда идут пн…вс в днях **клиента**. Конец интервала в полночь пишется как `24:00`, чтобы интервал не выглядел как «назад во времени».

```
TutorSlots report
Client offset: UTC+07:00
Earliest start: 08:00
Mon: 13:00-15:00, 16:00-21:00
Tue: 13:00-18:00
Wed: 13:00-16:00
Thu: 13:00-18:00
Fri: 15:00-20:00
Sat: 13:00-14:00
Sun: 13:00-15:00, 18:00-19:00
Free: 43
Busy: 62
Load: 59%
```

Выше — настоящий отчёт по `samples/snapshot.tsv` для клиента в UTC+7 (Новосибирск). Он сохранён как `samples/expected_utc+07.txt` и служит эталоном в тестах. День без окон выводится как `Tue: no free slots`.

Отчёт на английском, чтобы на Windows не было проблем с кодировкой консоли. Русский текст появляется только на картинке бота: его рисует Kotlin, и там с UTF-8 проблем нет.

## 4. Kotlin-часть

| Класс | Ответственность | Технология |
|---|---|---|
| `Config` | Переменные окружения `BOT_TOKEN`, `GOOGLE_API_KEY`, `SPREADSHEET_ID`, `SHEET_RANGE`, `TUTOR_ID` | — |
| `SheetsClient` | `GET https://sheets.googleapis.com/v4/spreadsheets/{id}?ranges=…&includeGridData=true&fields=sheets.data.rowData.values(formattedValue,effectiveFormat.backgroundColor)&key=…` → TSV | `java.net.http.HttpClient`, `kotlinx.serialization.json` |
| `ScheduleRepository` | Кэш на 5 минут; при ошибке — последний сохранённый `snapshot.tsv` | — |
| `UserSettingsStore` | Пояс каждого пользователя в `users.json`; запись через временный файл и переименование, чтобы файл не побился при сбое | `kotlinx.serialization` |
| `ZoneResolver` | Пресеты пояса РФ; разбор `+5:30`, `-4`, `UTC+7`, `Asia/Tokyo`; смещение на текущую дату | `java.time` |
| `SlotsService` | Собирает всё вместе, ничего не знает о Telegram | — |
| `SlotsRenderer` | Рисует PNG: карточка, 7 дней, «чипы» интервалов | Java2D (`BufferedImage`) |
| `TelegramBotApi` | Методы Bot API: `getUpdates`, `sendMessage`, `sendPhoto` (multipart), `answerCallbackQuery`, `setMyCommands`. Реализует интерфейс `BotApi`, поэтому в тестах подменяется фейком | `java.net.http`, без сторонних библиотек |
| `TutorBot` | Команды, инлайн-кнопки поясов, постоянная клавиатура, long polling | — |

**Цвет из API.** Google отдаёт `backgroundColor` как `{red, green, blue}` в долях 0..1, причём **нулевые каналы опускаются**: чисто зелёный приходит как `{"green": 1}`. Отсутствующий канал = 0, а отсутствующий `backgroundColor` = нет заливки. При переводе в `#RRGGBB` используется `round(x·255)`.

**Пресеты поясов** (кнопки `/start`): Калининград +2, Москва +3, Самара +4, Екатеринбург +5, Омск +6, Новосибирск/Красноярск +7, Иркутск +8, Якутск +9, Владивосток +10, Магадан +11, Камчатка +12, «Другой…».

**Почему без библиотеки для Telegram.** Боту нужны 6 методов Bot API, и каждый — это один HTTP-запрос с JSON. Своя реализация занимает около 100 строк. Она убирает зависимость, которая может устареть, и позволяет проверить точный формат запросов на локальном тестовом HTTP-сервере (`TelegramBotApiTest`).

**Язык сообщений.** Всё, что пишется в консоль и лог, — на английском: консоль Windows с Java 17 искажает кириллицу (дефект D-03). По-русски — только то, что видит пользователь: сообщения в Telegram и текст на картинке.

## 5. Структура репозитория

```
tutor-slots/
├── docs/            требования, архитектура, UML, сравнение, отчёт о тестах
├── samples/         snapshot.tsv + ожидаемые отчёты для тестов
├── core-cpp/
│   ├── CMakeLists.txt
│   ├── include/tutor_slots/core.hpp
│   ├── src/core.cpp, src/main.cpp
│   └── tests/core_test.cpp          (Google Test)
├── app-kotlin/
│   ├── build.gradle.kts, settings.gradle.kts
│   └── src/main/kotlin/…, src/test/kotlin/…
├── .github/workflows/ci.yml
├── .gitignore       (users.json, snapshot-cache, build/, .env)
└── README.md
```

## 6. Ветки Git

`main` ← PR из `docs/requirements`, `docs/design`, `feature/core-cpp`, `feature/kotlin-core`, `feature/bot`, `test/coverage`. Каждый этап проекта — отдельный PR, который проходит CI.
