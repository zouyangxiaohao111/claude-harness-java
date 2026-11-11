@echo off
REM ============================================================================
REM  Restart NexusAI backend on port 3458
REM
REM  WARNING: 3458 is the HOST process of the current agent session.
REM           Killing it from inside the session aborts the running turn.
REM           Run this script by double-clicking it on the desktop instead.
REM
REM  Steps:
REM    1) kill ONLY the java process listening on 3458 (by PID, never by port)
REM    2) relaunch it with the SAME classpath the original process used
REM       (target/classes + m2 deps, i.e. the IDEA run configuration)
REM ============================================================================
setlocal enabledelayedexpansion
set BACKEND=D:\code\ai_project\nexusai\backend
set CPFILE=D:\tmp\nexusai-3458-cp.txt
set LOGDIR=%BACKEND%\logs
set JAVAEXE=C:\Program Files\Java\jdk-25.0.3\bin\java.exe
set MAIN=com.nexusai.NexusAiApplication

echo [1/4] Looking for the process listening on 3458 ...
set PID=
for /f "tokens=5" %%p in ('netstat -ano ^| findstr ":3458" ^| findstr "LISTENING"') do set PID=%%p

if defined PID (
  echo     PID=!PID!
  echo [2/4] Exporting its classpath ...
  powershell -NoProfile -Command "$c=(Get-CimInstance Win32_Process -Filter 'ProcessId=!PID!').CommandLine; if ($c -match '-classpath (.+?) com\.nexusai\.') { [IO.File]::WriteAllText('%CPFILE%', $matches[1]); 'OK' } else { 'FAILED'; exit 1 }"
  if errorlevel 1 (
    echo     ERROR: could not parse -classpath from the command line. Aborting.
    pause
    exit /b 1
  )
  echo [3/4] Stopping PID=!PID! ...
  taskkill /PID !PID! /F
  timeout /t 5 /nobreak >nul
) else (
  echo     Nothing listening on 3458, starting fresh.
)

echo [4/4] Starting backend ...
if not exist "%CPFILE%" (
  echo     ERROR: missing classpath file %CPFILE%
  echo     If the old process is already gone, relaunch once from IDEA to
  echo     regenerate it, then run this script again.
  pause
  exit /b 1
)
set /p CP=<"%CPFILE%"

start "nexusai-3458" /min "%JAVAEXE%" -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 -classpath "%CP%" %MAIN% --server.port=3458 > "%LOGDIR%\console-restart.log" 2>&1

echo.
echo Started. After 60-90 seconds verify with:
echo   netstat -ano ^| findstr ":3458"
echo   findstr /C:"Started NexusAiApplication" "%LOGDIR%\console-restart.log"
echo.
pause
