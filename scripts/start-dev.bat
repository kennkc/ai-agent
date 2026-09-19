@echo off
setlocal enabledelayedexpansion
cd /d "%~dp0.."
set MODE=%1
if "%MODE%"=="" set MODE=all
set JWT_SECRET=agent-lifeform-dev-secret-key-2026-change-me-in-prod
set DEV_TOKEN_ENDPOINT_ENABLED=true
if "%JAVA_HOME%"=="" set JAVA_HOME=E:\software\java\jdk-21
rem 端口单一来源：WP_BFF_URL（Vite 代理目标）由 WP_BFF_PORT 派生
if "%WP_BFF_PORT%"=="" set WP_BFF_PORT=8090
if "%WP_BFF_URL%"=="" set WP_BFF_URL=http://127.0.0.1:%WP_BFF_PORT%
set JAVA_SERVICES=gateway-service session-manager sense-service body-service tool-executor
if "%MODE%"=="infra" goto :infra
if "%MODE%"=="java" goto :java
if "%MODE%"=="python" goto :python
if "%MODE%"=="bff" goto :bff
if "%MODE%"=="frontend" goto :frontend
if "%MODE%"=="all" goto :all
echo usage: start-dev.bat [all^|infra^|java^|python^|bff^|frontend]
exit /b 1
:infra
docker compose -f docker-compose.yml up -d
timeout /t 15 /nobreak >nul
goto :eof
:java
cd services\java
call mvn clean package -DskipTests -q
for %%S in (%JAVA_SERVICES%) do (
  for %%J in ("%%S\target\*.jar") do start "agent-lifeform-%%S" /D "%%S" cmd /c "java -jar %%~nxJ > %%S.log 2>&1"
)
cd ..\..
goto :eof
:python
cd services\python\nlp-service
if exist ..\venv\Scripts\python.exe (set PY=..\venv\Scripts\python.exe) else (set PY=python)
start "agent-lifeform-nlp" cmd /c "%PY% -m uvicorn app.main:app --host 0.0.0.0 --port 8000"
cd ..\..\..
goto :eof
rem wp-bff：工作平台 BFF。缺了它前端 API 模式会全线回落 Mock（必须显式起）
:bff
cd services\node\wp-bff
if not exist node_modules call npm install
start "agent-lifeform-wp-bff" cmd /c "set WP_BFF_PORT=%WP_BFF_PORT%&& node server.js"
cd ..\..\..
goto :eof
:frontend
cd web\work-platform
if not exist node_modules call npm install
start "agent-lifeform-work-platform" cmd /c "set WP_BFF_URL=%WP_BFF_URL%&& npm run dev -- --host 0.0.0.0 --port 3001"
cd ..\..
goto :eof
:all
call :infra
call :java
call :python
call :bff
call :frontend
timeout /t 8 /nobreak >nul
call scripts\healthcheck.bat
goto :eof