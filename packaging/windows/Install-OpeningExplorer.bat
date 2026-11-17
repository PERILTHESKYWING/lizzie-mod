@echo off
rem Replaces the program file of an installed LizzieYzy Next with the Opening Explorer build
rem that sits next to this script, keeping the original as a .bak backup.
rem Usage: double-click, or drag your LizzieYzy Next folder onto this file.
setlocal EnableExtensions
set "NEWJAR=%~dp0lizzie-yzy2.5.3-shaded.jar"
if not exist "%NEWJAR%" (
  echo Put this script in the same folder as lizzie-yzy2.5.3-shaded.jar and run it again.
  pause
  exit /b 1
)

rem Copying into Program Files needs administrator rights: ask once, then continue.
net session >nul 2>&1
if errorlevel 1 (
  powershell -NoProfile -Command "Start-Process -Verb RunAs -FilePath '%~f0' -ArgumentList '\"%~1\"'"
  exit /b
)

set "TARGET="
if not "%~1"=="" if exist "%~1\app\lizzie-yzy2.5.3-shaded.jar" set "TARGET=%~1\app"
if not defined TARGET for %%N in ("LizzieYzy Next" "LizzieYzy Next NVIDIA" "LizzieYzy Next NVIDIA TensorRT" "LizzieYzy Next OpenCL" "LizzieYzy Next DirectML Experimental") do (
  if not defined TARGET if exist "%ProgramFiles%\%%~N\app\lizzie-yzy2.5.3-shaded.jar" set "TARGET=%ProgramFiles%\%%~N\app"
  if not defined TARGET if exist "%LOCALAPPDATA%\Programs\%%~N\app\lizzie-yzy2.5.3-shaded.jar" set "TARGET=%LOCALAPPDATA%\Programs\%%~N\app"
)
if not defined TARGET (
  echo Could not find a LizzieYzy Next 2.5.3 installation with app\lizzie-yzy2.5.3-shaded.jar.
  echo If your LizzieYzy Next is installed somewhere else, drag its folder onto this script.
  echo If your version is older, install the full LizzieYzy Next installer instead.
  pause
  exit /b 1
)

tasklist /FI "IMAGENAME eq LizzieYzy Next.exe" 2>nul | find /I "LizzieYzy" >nul
if not errorlevel 1 (
  echo Please close LizzieYzy Next first, then run this script again.
  pause
  exit /b 1
)

if not exist "%TARGET%\lizzie-yzy2.5.3-shaded.jar.bak" (
  copy /Y "%TARGET%\lizzie-yzy2.5.3-shaded.jar" "%TARGET%\lizzie-yzy2.5.3-shaded.jar.bak" >nul || goto :failed
)
copy /Y "%NEWJAR%" "%TARGET%\lizzie-yzy2.5.3-shaded.jar" >nul || goto :failed
echo Done. The Opening Explorer build is installed in:
echo   %TARGET%
echo Start LizzieYzy Next and press K. To undo, run Restore-Original.bat.
pause
exit /b 0

:failed
echo Copying failed. Nothing else was changed.
pause
exit /b 1
