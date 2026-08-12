@echo off
REM =====================================================
REM  Agent-Lifeform 健康检查脚本 (Windows)
REM  验证: gateway/session/sense/nlp + 基础设施
REM =====================================================
cd /d "%~dp0.."

echo === Agent-Lifeform DEV 健康检查 (Windows) ===
echo.

set FAIL=0

call :check "gateway-service :8080" "http://127.0.0.1:8080/actuator/health" "UP"
call :check "session-manager :8081" "http://127.0.0.1:8081/actuator/health" "UP"
call :check "sense-service :8082" "http://127.0.0.1:8082/actuator/health" "UP"
call :check "nlp-service :8000" "http://127.0.0.1:8000/healthz" "ok"
call :check "Nacos :8848" "http://127.0.0.1:8848/nacos/" ""

echo.
if %FAIL%==0 (
  echo ✅ 全部服务健康 (7/7)
) else (
  echo ⚠️ %FAIL% 个服务异常，请检查 docker-compose 与日志
)
exit /b %FAIL%

:check
set NAME=%1
set URL=%2
set EXPECT=%3
curl -s -o nul -w "%%{http_code}" %URL% > %TEMP%\hc.txt 2>nul
set /p CODE=<%TEMP%\hc.txt
if "%CODE%"=="200" (
  echo   ✅ %NAME% - OK
) else (
  echo   ❌ %NAME% - HTTP %CODE%
  set /a FAIL+=1
)
goto :eof
