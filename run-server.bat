@echo off
title Live MCQ Server
echo ====================================================
echo           LIVE MCQ SERVER IS STARTING...
echo ====================================================
echo.
echo [INFO] Keeping this window open keeps the server alive.
echo [INFO] Do NOT close this window while using the app!
echo.
set JAVA_HOME=D:\Java
set PATH=D:\Java\bin;%PATH%
cd /d "%~dp0"
java -jar "target\live-mcq-server-0.1.0.jar"
echo.
echo [STOPPED] Server has stopped.
pause
