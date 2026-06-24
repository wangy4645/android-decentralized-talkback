@echo off
setlocal
cd /d "%~dp0.."
if not exist gradlew.bat (
  echo gradlew.bat not found
  exit /b 1
)
echo Running android-board-talkback and talkback-app unit tests...
call gradlew.bat :android-board-talkback:testDebugUnitTest :talkback-app:testDebugUnitTest --no-daemon %*
set EXIT_CODE=%ERRORLEVEL%
if %EXIT_CODE%==0 (
  echo.
  echo All unit tests passed.
) else (
  echo.
  echo Unit tests failed. See build\reports\tests\testDebugUnitTest\index.html
)
exit /b %EXIT_CODE%
