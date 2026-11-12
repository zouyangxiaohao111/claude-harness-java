@echo off
REM ============================================================================
REM  Restart NexusAI backend on 3458 WITH a full recompile.
REM
REM  Why a separate script: restart-3458.bat assumes target/classes is already
REM  fresh (IDEA built it). This session changed Java sources (span endpoints,
REM  P0 duplicate-mapping fix), so the classes must be rebuilt -- otherwise the
REM  restart silently loads the OLD code and the fix looks like it did nothing.
REM
REM  ORDER MATTERS: stop FIRST, then compile. Compiling while the backend runs
REM  overwrites target/classes under a live JVM and can throw
REM  NoClassDefFoundError in the middle of a request.
REM
REM  WARNING: 3458 hosts the current agent session. Double-click this file from
REM           Explorer. Do NOT run it from inside the agent session.
REM ============================================================================
setlocal enabledelayedexpansion
set BACKEND=D:\code\ai_project\nexusai\backend
set CPFILE=D:\tmp\nexusai-3458-cp.txt
set LOGDIR=%BACKEND%\logs
set JAVAEXE=C:\Program Files\Java\jdk-25.0.3\bin\java.exe
set MAIN=com.nexusai.NexusAiApplication

echo [1/6] Looking for the process listening on 3458 ...
set PID=
for /f "tokens=5" %%p in ('netstat -ano ^| findstr ":3458" ^| findstr "LISTENING"') do set PID=%%p

if defined PID (
  echo     PID=!PID!
  echo [2/6] Exporting its classpath ...
  powershell -NoProfile -Command "$c=(Get-CimInstance Win32_Process -Filter 'ProcessId=!PID!').CommandLine; if ($c -match '-classpath (.+?) com\.nexusai\.') { [IO.File]::WriteAllText('%CPFILE%', $matches[1]); 'OK' } else { 'FAILED'; exit 1 }"
  if errorlevel 1 (
    echo     ERROR: could not parse -classpath from the command line. Aborting.
    pause
    exit /b 1
  )
  echo [3/6] Stopping PID=!PID! ...
  taskkill /PID !PID! /F
  timeout /t 5 /nobreak >nul
) else (
  echo     Nothing listening on 3458, starting fresh.
)

echo [4/6] Compiling backend ...
cd /d "%BACKEND%"
call mvn -o -q compile -DskipTests
if errorlevel 1 (
  echo.
  echo     COMPILE FAILED. Backend NOT started. Fix the errors above first.
  pause
  exit /b 1
)
echo     Compile OK.

echo [5/6] Checking classpath file ...
if not exist "%CPFILE%" (
  echo     ERROR: missing %CPFILE%
  echo     Relaunch once from IDEA to regenerate it, then rerun this script.
  pause
  exit /b 1
)
set /p CP=<"%CPFILE%"

echo [6/6] Starting backend ...
start "nexusai-3458" /min "%JAVAEXE%" -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 -classpath "%CP%" %MAIN% --server.port=3458 > "%LOGDIR%\console-restart.log" 2>&1

echo.
echo Started. Verify after 60-90 seconds:
echo   netstat -ano ^| findstr ":3458"
echo   findstr /C:"Started NexusAiApplication" "%LOGDIR%\console-restart.log"
echo.
pause
