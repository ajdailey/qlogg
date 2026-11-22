@echo off
rem Starts QLogg with the bundled runtime, else JAVA_HOME, else javaw.exe on
rem PATH, and returns at once. Quote an argument holding a space or & | < > ^.
setlocal DisableDelayedExpansion
set "javaw=%~dp0runtime\bin\javaw.exe"
if exist "%javaw%" goto run
set "javaw=%JAVA_HOME%\bin\javaw.exe"
if defined JAVA_HOME if exist "%javaw%" goto run
set "javaw="
for %%j in (javaw.exe) do set "javaw=%%~$PATH:j"
if defined javaw goto run
>&2 echo QLogg needs Java 21: no runtime folder beside qlogg.cmd, no javaw.exe under JAVA_HOME or on PATH.
rem Keeps a double-clicked console open on the message.
pause
exit /b 1

:run
rem /b gives Java this console's output, so -v, -h and errors print there.
start "" /b "%javaw%" -XX:MaxRAMPercentage=50 -XX:GCTimeRatio=1 -jar "%~dp0lib\qlogg.jar" %*
