@echo off
cd /d "%~dp0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0deploy\windows\backend-preview.ps1" -Action stop
if errorlevel 1 (pause & exit /b 1)
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0deploy\windows\control.ps1" -Action stop
if errorlevel 1 pause
