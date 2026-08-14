@echo off
chcp 936 >nul
title YOLO-RPC Stopper

echo ============================================
echo   YOLO-RPC stopping...
echo ============================================

for /f "tokens=5" %%a in ('netstat -ano ^| findstr ":5173 :8001 :8002 :8003 :8080 :8848 :9000" ^| findstr LISTENING') do (
    echo  Killing PID: %%a
    taskkill /F /PID %%a >nul 2>&1
)

echo.
echo   All services stopped
echo ============================================
pause
