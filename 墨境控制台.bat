@echo off
setlocal DisableDelayedExpansion
"%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\dev-windows.ps1" %*
set "INK_EXIT_CODE=%ERRORLEVEL%"
if not "%INK_EXIT_CODE%"=="0" if "%~1"=="" pause
if not "%INK_EXIT_CODE%"=="0" if /i "%~1"=="menu" pause
exit /b %INK_EXIT_CODE%
