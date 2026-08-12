@echo off
REM =====================================================
REM  Agent-Lifeform 开发环境一键启动脚本 (Windows)
REM  用法: start-dev.bat [all|infra|java|python|console]
REM  DEV 环境默认 DATA_SOURCE=mock
REM =====================================================
setlocal enabledelayedexpansion
cd /d "%~dp0.."

set MODE=%1
if "%MODE%"=="" set MODE=all

echo ==============================================
echo  Agent-Lifeform DEV (Windows) - %MODE%
echo ==============================================

if "%MODE%"=="infra" goto :infra
if "%MODE%"=="java" goto :java
if "%MODE%"=="python" goto :python
if "%MODE%"=="console" goto :console
if "%MODE%"=="all" goto :all
echo 用法: start-dev.bat [all^|infra^|java^|python^|console]
exit /b 1

:infra
echo [1] 启动基础设施 (Redis/PG/Qdrant/NATS/Nacos/MinIO)...
docker compose -f docker-compose.yml up -d
echo     等待基础设施健康...
timeout /t 15 /nobreak >nul
docker compose -f docker-compose.yml ps
goto :eof

:java
echo [2] 构建并启动 Java 服务 (gateway/session/sense)...
cd services\java
call mvn clean package -DskipTests -q
cd ..\..
echo     请分别启动各服务: mvn spring-boot:run (在对应服务目录)
goto :eof

:python
echo [3] 启动 Python 服务 (nlp-service :8000)...
cd services\python\nlp-service
python -m uvicorn app.main:app --host 0.0.0.0 --port 8000
goto :eof

:console
echo [4] 启动 Console 原型 (Mock 数据源)...
cd web\console
start "" index.html
goto :eof

:all
call :infra
call :java
call :python
echo.
echo === DEV 环境启动完成 (Mock 模式) ===
call scripts\healthcheck.bat
goto :eof
