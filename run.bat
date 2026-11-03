@echo off
setlocal

if defined MAVEN_HOME set "PATH=%MAVEN_HOME%\bin;%PATH%"
where mvn >nul 2>nul
if errorlevel 1 (
    echo Maven is required. Install Maven 3.9+ and add it to PATH, then run this script again.
    exit /b 1
)

call mvn -q compile exec:java
exit /b %errorlevel%