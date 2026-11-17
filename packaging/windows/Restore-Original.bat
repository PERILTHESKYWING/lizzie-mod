@echo off
rem Puts back the original LizzieYzy Next program file saved by Install-OpeningExplorer.bat.
setlocal EnableExtensions
net session >nul 2>&1
if errorlevel 1 (
  powershell -NoProfile -Command "Start-Process -Verb RunAs -FilePath '%~f0' -ArgumentList '\"%~1\"'"
  exit /b
)
set "TARGET="
if not "%~1"=="" if exist "%~1\app\lizzie-yzy2.5.3-shaded.jar.bak" set "TARGET=%~1\app"
if not defined TARGET for %%N in ("LizzieYzy Next" "LizzieYzy Next NVIDIA" "LizzieYzy Next NVIDIA TensorRT" "LizzieYzy Next OpenCL" "LizzieYzy Next DirectML Experimental") do (
  if not defined TARGET if exist "%ProgramFiles%\%%~N\app\lizzie-yzy2.5.3-shaded.jar.bak" set "TARGET=%ProgramFiles%\%%~N\app"
  if not defined TARGET if exist "%LOCALAPPDATA%\Programs\%%~N\app\lizzie-yzy2.5.3-shaded.jar.bak" set "TARGET=%LOCALAPPDATA%\Programs\%%~N\app"
)
if not defined TARGET (
  echo No backup found. If LizzieYzy Next is installed somewhere else, drag its folder onto this script.
  pause
  exit /b 1
)
copy /Y "%TARGET%\lizzie-yzy2.5.3-shaded.jar.bak" "%TARGET%\lizzie-yzy2.5.3-shaded.jar" >nul || (
  echo Restoring failed.
  pause
  exit /b 1
)
echo The original LizzieYzy Next program file is back in %TARGET%.
pause
