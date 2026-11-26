# core-cpp — ядро TutorSlots на C++

Читает снимок расписания (`samples/snapshot.tsv`), определяет свободные слоты и печатает отчёт во времени клиента.

## Сборка в Visual Studio 2022

1. Убедитесь, что в Visual Studio Installer отмечена рабочая нагрузка **«Разработка классических приложений на C++»** (в неё входят CMake и MSVC).
2. **Файл → Открыть → Папку…** и выберите папку `core-cpp`. Visual Studio сама найдёт `CMakeLists.txt` и запустит настройку. При первой настройке CMake скачает Google Test с GitHub (нужен интернет).
3. В списке целей запуска выберите `tutor_slots.exe` или `core_tests.exe`, затем **Сборка → Собрать всё**.
4. Тесты: **Тест → Обозреватель тестов → Запустить все**.

## Сборка из командной строки

Запускать в «Developer PowerShell for VS 2022»:

```powershell
cmake -S core-cpp -B build
cmake --build build --config Debug
ctest --test-dir build -C Debug --output-on-failure
```

## Запуск

```powershell
build\Debug\tutor_slots.exe samples\snapshot.tsv --offset +07:00
build\Debug\tutor_slots.exe samples\snapshot.tsv --offset +5:30 --out report.txt
build\Debug\tutor_slots.exe samples\snapshot.tsv --offset +07:00 --earliest 00:00   # без фильтра раннего времени
```

| Код выхода | Значение |
|---|---|
| 0 | Отчёт сформирован (предупреждения, если есть, выводятся в stderr) |
| 1 | Неверные аргументы |
| 2 | Не удалось открыть входной или выходной файл |
| 3 | В файле нет ни одного корректного слота |

## Состав

| Файл | Что внутри |
|---|---|
| `include/tutor_slots/core.hpp` | Типы и классы ядра |
| `src/core.cpp` | Реализация: чтение файла, классификация цвета, сдвиг пояса, интервалы, статистика, отчёт |
| `src/main.cpp` | Консольная обёртка |
| `tests/core_test.cpp` | 37 тестов Google Test, названия ссылаются на критерии приёмки (AC-xx) |
