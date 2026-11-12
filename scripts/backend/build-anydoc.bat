@echo off
REM ============================================================================
REM  build-anydoc.bat  -  Windows wrapper for build-anydoc.sh
REM
REM  Compiles the anydoc native library (anydoc_go.dll) and drops it into
REM  backend\src\main\resources\anydoc\x64-win32\  so it ships inside the jar.
REM
REM  Requires Git for Windows (provides bash.exe). The Rust toolchain is
REM  checked by the shell script itself.
REM
REM  Usage:
REM    double-click                  -> build for this machine
REM    build-anydoc.bat --check      -> dry run (toolchain / platform / source)
REM    build-anydoc.bat --clone      -> clone WeKnora first if sources missing
REM ============================================================================
setlocal enabledelayedexpansion

set "SH=%~dp0build-anydoc.sh"
if not exist "%SH%" (
  echo ERROR: missing "%SH%"
  pause
  exit /b 1
)

REM Locate bash.exe: PATH first, then the usual Git for Windows install paths.
set "BASH="
for %%p in (bash.exe) do if not defined BASH set "BASH=%%~$PATH:p"
if not defined BASH if exist "C:\Program Files\Git\bin\bash.exe" set "BASH=C:\Program Files\Git\bin\bash.exe"
if not defined BASH if exist "C:\Program Files (x86)\Git\bin\bash.exe" set "BASH=C:\Program Files (x86)\Git\bin\bash.exe"
if not defined BASH if exist "%LOCALAPPDATA%\Programs\Git\bin\bash.exe" set "BASH=%LOCALAPPDATA%\Programs\Git\bin\bash.exe"

if not defined BASH (
  echo ERROR: bash.exe not found.
  echo Install Git for Windows: https://git-scm.com/download/win
  echo then run this script again.
  pause
  exit /b 1
)

echo Using bash: !BASH!
echo.

REM Convert the .sh path to a POSIX path bash understands.
REM WHY a temp file instead of `for /f ... in (\`...\`)`:
REM   the bash path contains spaces ("C:\Program Files\...") and cmd's
REM   backquote form mangles nested quotes -> "'C:\Program' is not recognized".
REM   Writing to a temp file and reading it back is quote-proof.
set "TMPOUT=%TEMP%\build-anydoc-path.txt"
del /q "%TMPOUT%" >nul 2>&1
"!BASH!" -c "cygpath -u \"%SH%\"" > "%TMPOUT%" 2>nul
set "SHPOSIX="
for /f "usebackq delims=" %%i in ("%TMPOUT%") do if not defined SHPOSIX set "SHPOSIX=%%i"
del /q "%TMPOUT%" >nul 2>&1

if not defined SHPOSIX (
  echo ERROR: could not convert path to POSIX form: "%SH%"
  pause
  exit /b 1
)
echo Script: !SHPOSIX!
echo.

"!BASH!" "!SHPOSIX!" %*
set "RC=!ERRORLEVEL!"

echo.
if "!RC!"=="0" (
  echo Build finished successfully.
) else (
  echo Build FAILED with exit code !RC!. Scroll up for the reason.
)
pause
exit /b !RC!
