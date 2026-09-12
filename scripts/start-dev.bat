@echo off
setlocal enabledelayedexpansion
cd /d "%~dp0.."
set MODE=%1
if "%MODE%"=="" set MODE=all
set JWT_SECRET=agent-lifeform-dev-secret-key-2026-change-me-in-prod
set DEV_TOKEN_ENDPOINT_ENABLED=true
if "%JAVA_HOME%"=="" set JAVA_HOME=E:\software\java\jdk-21
if "%MODE%"=="infra" goto :infra
if "%MODE%"=="java" goto :java
if "%MODE%"=="python" goto :python
if "%MODE%"=="frontend" goto :frontend
if "%MODE%"=="all" goto :all
echo usage: start-dev.bat [all^|infra^|java^|python^|frontend]
exit /b 1
:infra
docker compose -f docker-compose.yml up -d
timeout /t 15 /nobreak >nul
goto :eof
:java
cd services\java
call mvn clean package -DskipTests -q
for %%S in (gateway-service session-manager sense-service body-service) do (
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
:frontend
cd web\work-platform
if not exist node_modules call npm install
start "agent-lifeform-work-platform" cmd /c "npm run dev -- --host 0.0.0.0 --port 3001"
cd ..\..
goto :eof
:all
call :infra
call :java
call :python
call :frontend
timeout /t 8 /nobreak >nul
call scripts\healthcheck.bat
goto :eof
