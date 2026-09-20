#!/usr/bin/env pwsh
# 校验本机开发/部署工具版本是否满足 versions.lock.json 的最低要求。
# 只检查 CLI 存在性与版本，不启动 Docker daemon、不修改系统环境。
param(
    [string]$ManifestPath = (Join-Path $PSScriptRoot '..\versions.lock.json')
)

$ErrorActionPreference = 'Continue'
$Toolchain = (Get-Content -LiteralPath $ManifestPath -Raw -Encoding utf8) | ConvertFrom-Json
$fail = 0
$warn = 0

function Get-VersionText([string]$command, [string[]]$arguments) {
    try {
        $output = & $command @arguments 2>&1 | Out-String
        return ($output.Trim() -split "`r?`n")[0]
    } catch {
        return ''
    }
}

function Check-Command([string]$name, [string]$command, [string[]]$arguments, [string]$expected) {
    $text = Get-VersionText $command $arguments
    if (-not $text) {
        Write-Host ("FAIL {0}: 未找到或无法执行（要求 {1}）" -f $name, $expected) -ForegroundColor Red
        $script:fail++
        return
    }
    Write-Host ("PASS {0}: {1}（要求 {2}）" -f $name, $text, $expected) -ForegroundColor Green
}

function Check-Python() {
    $text = Get-VersionText 'python' @('--version')
    if (-not $text) {
        Write-Host ("WARN Python: 未找到 python 命令；项目需 Python {0}" -f $Toolchain.required_runtime.python) -ForegroundColor Yellow
        $script:warn++
        return
    }
    if ($text -match '3\.12\.') {
        Write-Host ("PASS Python: {0}（要求 {1}）" -f $text, $Toolchain.required_runtime.python) -ForegroundColor Green
    } else {
        Write-Host ("WARN Python: 当前 python={0}；项目要求 {1}，请使用 services/python/venv 或 python3.12" -f $text, $Toolchain.required_runtime.python) -ForegroundColor Yellow
        $script:warn++
    }
}

Write-Host '=== Agent-Lifeform toolchain check ==='
Check-Command 'Java' 'java' @('-version') $Toolchain.required_runtime.java
Check-Command 'Maven' 'mvn' @('-version') $Toolchain.required_runtime.maven
Check-Python
Check-Command 'Node' 'node' @('--version') $Toolchain.required_runtime.node
Check-Command 'npm' 'npm' @('--version') $Toolchain.required_runtime.npm
Check-Command 'Git' 'git' @('--version') 'Git 已安装'

$dockerVersion = Get-VersionText 'docker' @('--version')
$composeVersion = Get-VersionText 'docker' @('compose', 'version')
if ($dockerVersion) { Write-Host ("PASS Docker: {0}" -f $dockerVersion) -ForegroundColor Green }
else { Write-Host 'WARN Docker: CLI 不在此 shell PATH 中，应用服务控制页不可用' -ForegroundColor Yellow; $warn++ }
if ($composeVersion) { Write-Host ("PASS Docker Compose: {0}" -f $composeVersion) -ForegroundColor Green }
else { Write-Host 'WARN Docker Compose: 未检测到 Compose v2 插件' -ForegroundColor Yellow; $warn++ }

Write-Host ''
Write-Host '参考版本（versions.lock.json）：'
Write-Host ("  Java={0}, Maven={1}, Python={2}, Node={3}, npm={4}" -f `
    $Toolchain.host_reference.java, $Toolchain.host_reference.maven, `
    $Toolchain.host_reference.python, $Toolchain.host_reference.node, $Toolchain.host_reference.npm)
Write-Host ''
Write-Host ("结果：FAIL={0} WARN={1}" -f $fail, $warn)
exit ($(if ($fail -gt 0) { 1 } else { 0 }))