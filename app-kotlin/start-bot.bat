@echo off
rem Starts the TutorSlots Telegram bot. Turn on the VPN first if Telegram is blocked.
cd /d "%~dp0"
if not exist bot.env (
  echo bot.env not found. Copy bot.env.example to bot.env and fill it in.
  pause
  exit /b 1
)
echo Starting TutorSlots bot... Close this window or press Ctrl+C to stop.
call gradlew.bat run --quiet
pause
