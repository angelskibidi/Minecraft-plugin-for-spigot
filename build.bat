@echo off
setlocal
cd /d "%~dp0"

set "JDK_BIN=C:\Program Files\Java\jdk-26\bin"
set "API_JAR=libs\spigot-api.jar"
set "SOURCE=src\main\java\me\angel\starter\StarterPlugin.java"

if not exist "%JDK_BIN%\javac.exe" (
    echo Could not find javac at "%JDK_BIN%\javac.exe".
    echo Edit JDK_BIN near the top of build.bat if your JDK is installed elsewhere.
    exit /b 1
)

if not exist "%API_JAR%" (
    echo Could not find "%API_JAR%".
    echo Put the downloaded Spigot API jar at libs\spigot-api.jar.
    exit /b 1
)

if not exist "%SOURCE%" (
    echo Could not find the plugin Java source file:
    echo %SOURCE%
    exit /b 1
)

if exist build\classes rmdir /s /q build\classes
mkdir build\classes

"%JDK_BIN%\javac.exe" --release 26 -encoding UTF-8 -classpath "%API_JAR%" -d build\classes "%SOURCE%"
if errorlevel 1 exit /b 1

copy /Y src\main\resources\plugin.yml build\classes\plugin.yml >nul
if errorlevel 1 exit /b 1

if exist build\StarterPlugin.jar del /q build\StarterPlugin.jar
"%JDK_BIN%\jar.exe" --create --file build\StarterPlugin.jar -C build\classes .
if errorlevel 1 exit /b 1

echo.
echo Built build\StarterPlugin.jar
