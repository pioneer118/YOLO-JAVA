@echo off
chcp 936 >nul
title YOLO-RPC Launcher
setlocal

REM ============================================================
REM  YOLO-RPC Distributed Object Detection - One-Click Start
REM ============================================================

REM ---------- Config (edit if needed) ----------
set "PROJECT=C:\Users\Öç\IdeaProjects\YOLO-RPC"
set "JAVA=D:\develop\JDK\bin\java.exe"
set "ONNX_LIB=C:\Users\Öç\tools\onnxruntime\onnxruntime-win-x64-1.26.0\lib\onnxruntime.dll"
set "NACOS_JAR=C:\Users\Öç\tools\nacos\target\nacos-server.jar"
set "MODELS=%PROJECT%\models"
set "LOGS=%PROJECT%\logs"
REM ----------------------------------------

echo.
echo ============================================
echo   YOLO-RPC Starting...
echo ============================================
echo.

if not exist "%LOGS%" mkdir "%LOGS%"

REM ---------- Check env ----------
if not exist "%JAVA%" (
    echo [ERROR] Java not found: %JAVA%
    pause
    exit /b 1
)
if not exist "%ONNX_LIB%" (
    echo [ERROR] ONNX Runtime not found: %ONNX_LIB%
    pause
    exit /b 1
)
if not exist "%NACOS_JAR%" (
    echo [ERROR] Nacos not found: %NACOS_JAR%
    pause
    exit /b 1
)

REM ---------- 1. Nacos ----------
echo [1/6] Starting Nacos (8848)...
powershell -NoProfile -Command "Start-Process -FilePath '%JAVA%' -ArgumentList '-Xms512m','-Xmx512m','-Dnacos.standalone=true','-jar','%NACOS_JAR%' -WindowStyle Hidden -RedirectStandardOutput '%LOGS%\nacos.log' -RedirectStandardError '%LOGS%\nacos.err.log'"
call :wait_port 8848 "Nacos"

REM ---------- 2. yolo-server x3 ----------
echo [2/6] Starting yolo-server (ship:8001 / plane:8002 / car:8003)...
powershell -NoProfile -Command "Start-Process -FilePath '%JAVA%' -ArgumentList '-Xmx1536m','-Donnxruntime.lib.path=%ONNX_LIB%','-jar','%PROJECT%\yolo-server\target\yolo-server-1.0.0.jar','--yolo.model-path=%MODELS%\bestship.onnx','--yolo.model-type=ship','--dubbo.protocol.port=8001','--server.port=18001','--management.server.port=18101','--dubbo.application.qos-enable=false' -WindowStyle Hidden -RedirectStandardOutput '%LOGS%\server-ship.log' -RedirectStandardError '%LOGS%\server-ship.err.log'"
powershell -NoProfile -Command "Start-Process -FilePath '%JAVA%' -ArgumentList '-Xmx1536m','-Donnxruntime.lib.path=%ONNX_LIB%','-jar','%PROJECT%\yolo-server\target\yolo-server-1.0.0.jar','--yolo.model-path=%MODELS%\bestplane.onnx','--yolo.model-type=plane','--dubbo.protocol.port=8002','--server.port=18002','--management.server.port=18102','--dubbo.application.qos-enable=false' -WindowStyle Hidden -RedirectStandardOutput '%LOGS%\server-plane.log' -RedirectStandardError '%LOGS%\server-plane.err.log'"
powershell -NoProfile -Command "Start-Process -FilePath '%JAVA%' -ArgumentList '-Xmx1g','-Donnxruntime.lib.path=%ONNX_LIB%','-jar','%PROJECT%\yolo-server\target\yolo-server-1.0.0.jar','--yolo.model-path=%MODELS%\bestcar.onnx','--yolo.model-type=car','--dubbo.protocol.port=8003','--server.port=18003','--management.server.port=18103','--dubbo.application.qos-enable=false' -WindowStyle Hidden -RedirectStandardOutput '%LOGS%\server-car.log' -RedirectStandardError '%LOGS%\server-car.err.log'"
call :wait_port 8001 "ship"
call :wait_port 8002 "plane"
call :wait_port 8003 "car"

REM ---------- 3. Gateway ----------
echo [3/6] Starting gateway (9000)...
powershell -NoProfile -Command "Start-Process -FilePath '%JAVA%' -ArgumentList '-Xmx1g','-jar','%PROJECT%\yolo-gateway\target\yolo-gateway-1.0.0.jar','--dubbo.protocol.port=9000' -WindowStyle Hidden -RedirectStandardOutput '%LOGS%\gateway.log' -RedirectStandardError '%LOGS%\gateway.err.log'"
call :wait_port 9000 "gateway"

REM ---------- 4. Web API ----------
echo [4/6] Starting web-api (8080)...
powershell -NoProfile -Command "Start-Process -FilePath '%JAVA%' -ArgumentList '-Xmx384m','-jar','%PROJECT%\yolo-web-api\target\yolo-web-api-1.0.0.jar','--server.port=8080' -WindowStyle Hidden -RedirectStandardOutput '%LOGS%\web-api.log' -RedirectStandardError '%LOGS%\web-api.err.log'"
call :wait_port 8080 "web-api"

REM ---------- 5. Frontend ----------
echo [5/6] Starting frontend (5173)...
start "YOLO-Frontend" cmd /k "cd /d %PROJECT%\yolo-web && npx vite --port 5173 --host"
call :wait_port 5173 "frontend"

REM ---------- 6. Verify ----------
echo [6/6] Verifying...
timeout /t 3 /nobreak >nul
curl -s http://localhost:8080/api/health
echo.
echo.
echo ============================================
echo   STARTED OK!
echo ============================================
echo   Frontend:  http://localhost:5173
echo   Health:    http://localhost:8080/api/health
echo   Nacos:     http://localhost:8848/nacos
echo.
echo   Logs:      %LOGS%
echo   Stop:      run stop.bat
echo ============================================
echo.
pause
exit /b 0

REM ---------- wait for port ----------
:wait_port
set "_port=%~1"
set "_name=%~2"
echo   ...waiting %_name% (%_port%)...
:wait_loop
timeout /t 3 /nobreak >nul
netstat -ano | findstr ":%_port% " | findstr LISTENING >nul
if errorlevel 1 goto wait_loop
echo   [OK] %_name% ready
goto :eof
