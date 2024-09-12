@echo off
setlocal

rem Set Java source file paths
set CLIENT_SRC=TerminalClient.java
set SERVER_SRC=TerminalServer.java

rem Set output JAR file names
set CLIENT_JAR=TerminalClient.jar
set SERVER_JAR=TerminalServer.jar

rem Set manifest file names
set CLIENT_MANIFEST=client_manifest.mf
set SERVER_MANIFEST=server_manifest.mf

rem Delete existing JAR and class files
echo Deleting existing JAR and class files...
del /q %CLIENT_JAR%
del /q %SERVER_JAR%
del /q *.class

rem Check if JDK is installed
if not defined JAVA_HOME (
    echo JAVA_HOME is not set. Please set JAVA_HOME to your JDK installation path.
    pause
    exit /b 1
)

rem Ensure javac and jar commands are available
set PATH=%JAVA_HOME%\bin;%PATH%

rem Compile Java source files with Java 6 compatibility
echo Compiling Java files...
javac -source 1.6 -target 1.6 %CLIENT_SRC% %SERVER_SRC%
if errorlevel 1 (
    echo Failed to compile Java files
    pause
    exit /b 1
)

rem Create manifest files
echo Creating client manifest file...
(
    echo Main-Class: TerminalClient
) > %CLIENT_MANIFEST%

echo Creating server manifest file...
(
    echo Main-Class: TerminalServer
) > %SERVER_MANIFEST%

rem Create JAR files
echo Creating client JAR file...
jar cfm %CLIENT_JAR% %CLIENT_MANIFEST% TerminalClient*.class
if errorlevel 1 (
    echo Failed to create %CLIENT_JAR%
    pause
    exit /b 1
)

echo Creating server JAR file...
jar cfm %SERVER_JAR% %SERVER_MANIFEST% TerminalServer*.class
if errorlevel 1 (
    echo Failed to create %SERVER_JAR%
    pause
    exit /b 1
)

rem Clean up
del %CLIENT_MANIFEST%
del %SERVER_MANIFEST%

echo Compilation and packaging complete.
pause
endlocal
exit /b 0