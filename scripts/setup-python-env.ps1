<#
.SYNOPSIS
  为 nlp-service 创建隔离 Python 环境并安装依赖（一键复现，含 pytest 冒烟验证）。

.DESCRIPTION
  1. 创建 services\python\venv（仓库既有约定路径，全仓文档统一引用 ../venv/Scripts/python.exe）
  2. 以 UTF-8 模式安装 requirements.txt + requirements-dev.txt
  3. 运行 pytest 冒烟验证（-SkipTest 可跳过）

  为什么要显式 UTF-8：pip 在中文 Windows 上默认按 GBK 读取依赖文件，遇到非 ASCII
  内容会抛 UnicodeDecodeError；装进项目 venv 也避免污染系统 Python。

  路径为什么不是 .venv：仓库自 Phase 2 起统一使用 services\python\venv，
  docs/demo/Phase2-Demo ~ Phase5-Demo、docs/proto契约使用说明、docs/java-services
  等 15+ 处命令都写作 ../venv/Scripts/python.exe；.gitignore 也按该路径忽略。
  需要别的位置时用 -VenvPath 显式指定，不要默认另起一套。

.EXAMPLE
  pwsh -File scripts/setup-python-env.ps1

.EXAMPLE
  pwsh -File scripts/setup-python-env.ps1 -Recreate -SkipTest
#>
[CmdletBinding()]
param(
  [switch]$Recreate,
  [switch]$SkipTest,
  [string]$VenvPath
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$svcDir = Join-Path $repoRoot "services\python\nlp-service"
if ($VenvPath) {
  $venvDir = $VenvPath
} else {
  $venvDir = Join-Path $repoRoot "services\python\venv"
}
$venvPython = Join-Path $venvDir "Scripts\python.exe"

# pip 读取依赖文件时的编码保护（中文 Windows 默认 GBK）
$env:PYTHONUTF8 = "1"
$env:PYTHONIOENCODING = "utf-8"

if (-not (Test-Path $svcDir)) { throw "service dir not found: $svcDir" }

if ($Recreate -and (Test-Path $venvDir)) {
  Write-Host "[1/4] 删除旧 venv: $venvDir"
  Remove-Item -Recurse -Force $venvDir
}

if (Test-Path $venvPython) {
  Write-Host "[1/4] 复用已有 venv: $venvDir"
} else {
  Write-Host "[1/4] 创建 venv: $venvDir"
  python -m venv $venvDir
}

Write-Host "[2/4] 升级 pip"
& $venvPython -m pip install -q --disable-pip-version-check --upgrade pip

Write-Host "[3/4] 安装依赖（requirements.txt + requirements-dev.txt）"
& $venvPython -m pip install -q --disable-pip-version-check -r (Join-Path $svcDir "requirements.txt") -r (Join-Path $svcDir "requirements-dev.txt")

if ($SkipTest) {
  Write-Host "[4/4] 已跳过 pytest 冒烟验证"
} else {
  Write-Host "[4/4] pytest 冒烟验证"
  Push-Location $svcDir
  try { & $venvPython -m pytest -q } finally { Pop-Location }
}

Write-Host ""
Write-Host "环境就绪: $venvPython"
Write-Host "常用命令:"
Write-Host "  cd $svcDir"
Write-Host "  ..\venv\Scripts\python.exe -m pytest -q     # 仓库统一写法：基线 199 passed / 1 skipped"
Write-Host "  ..\venv\Scripts\python.exe -m uvicorn app.main:app --port 8000"
