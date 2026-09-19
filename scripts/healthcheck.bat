@echo off
cd /d "%~dp0.."
setlocal enabledelayedexpansion
set FAIL=0
call :check "gateway-service :8080" "http://127.0.0.1:8080/actuator/health"
call :check "session-manager :8081" "http://127.0.0.1:8081/actuator/health"
call :check "sense-service :8082" "http://127.0.0.1:8082/actuator/health"
call :check "body-service :8083" "http://127.0.0.1:8083/actuator/health"
call :check "tool-executor :8084" "http://127.0.0.1:8084/api/tool/health"
call :check "nlp-service :8000" "http://127.0.0.1:8000/healthz"
call :check "wp-bff :8090" "http://127.0.0.1:8090/api/wp/healthz"
call :check "work-platform :3001" "http://127.0.0.1:3001"
if %FAIL%==0 (echo all services healthy) else (echo %FAIL% checks failed)
exit /b %FAIL%
:check
curl -s -o nul -w "%%{http_code}" %2 > "%TEMP%\hc.txt" 2>nul
set /p CODE=<"%TEMP%\hc.txt"
if "%CODE%"=="200" (echo OK %~1) else (echo BAD %~1 HTTP %CODE% & set /a FAIL+=1)
goto :eof
