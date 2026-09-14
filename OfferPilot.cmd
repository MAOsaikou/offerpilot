@echo off
setlocal
chcp 65001 >nul
title OfferPilot - Project Console
cd /d "%~dp0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0deploy\windows\console.ps1" %*
if errorlevel 1 pause
