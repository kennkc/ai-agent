@echo off
setlocal
cd /d "%~dp0.."
if not exist services\python\nlp-service\generated mkdir services\python\nlp-service\generated
if exist services\python\venv\Scripts\python.exe (set PY=services\python\venv\Scripts\python.exe) else (set PY=python)
%PY% -m grpc_tools.protoc -I proto --python_out=services\python\nlp-service\generated --grpc_python_out=services\python\nlp-service\generated proto\common\v1\*.proto proto\session\v1\*.proto proto\brain\v1\*.proto proto\sensor\v1\*.proto proto\body\v1\*.proto proto\limb\v1\*.proto
if errorlevel 1 exit /b 1
echo Python proto generated.
