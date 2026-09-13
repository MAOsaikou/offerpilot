@echo off
cd /d "%~dp0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0deploy\windows\prepare.ps1"
if errorlevel 1 (pause & exit /b 1)
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0deploy\windows\control.ps1" -Action reload
if errorlevel 1 pause
