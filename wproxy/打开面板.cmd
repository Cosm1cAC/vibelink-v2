@echo off
title wproxy - dashboard
where pwsh >nul 2>nul
if errorlevel 1 (
  echo [ERROR] PowerShell 7 not found. Install: winget install Microsoft.PowerShell
  pause
  exit /b 1
)
pwsh -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0wproxy.ps1" -Action ui
