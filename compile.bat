@echo off
setlocal

set JAVA_VERSION=1.8
set BUILD_DIR=build
set DIST_DIR=dist
set LOGS_DIR=logs

if not defined JAVA_HOME (
    echo JAVA_HOME is not set. Please set JAVA_HOME to your JDK installation path.
    pause
    exit /b 1
)

set PATH=%JAVA_HOME%\bin;%PATH%

mkdir %BUILD_DIR% %DIST_DIR% %LOGS_DIR% 2>nul
del /q %DIST_DIR%\*.jar %BUILD_DIR%\*.class 2>nul

echo Compiling Java files...
javac -source %JAVA_VERSION% -target %JAVA_VERSION% -d %BUILD_DIR% TerminalClient.java TerminalServer.java
if errorlevel 1 (
    echo Compilation failed!
    pause
    exit /b 1
)

cd %BUILD_DIR%

(echo Manifest-Version: 1.0
 echo Main-Class: TerminalClient
) > client_manifest.mf

(echo Manifest-Version: 1.0
 echo Main-Class: TerminalServer
) > server_manifest.mf

jar cfm ..\%DIST_DIR%\TerminalClient.jar client_manifest.mf *.class
jar cfm ..\%DIST_DIR%\TerminalServer.jar server_manifest.mf TerminalServer*.class

cd ..
rmdir /s /q %BUILD_DIR%

echo Build completed successfully!
pause
endlocal