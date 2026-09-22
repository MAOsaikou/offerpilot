param(
    [ValidateSet('menu','start','stop','status','start-frontend','stop-frontend','update-frontend','start-backend','restart-backend','package','config','logs')]
    [string]$Action = 'menu',
    [switch]$DryRun
)
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$powershell = Join-Path $PSHOME 'powershell.exe'
$backendJar = Join-Path $projectRoot 'backend/offerpilot-bootstrap/target/offerpilot-bootstrap-0.1.0-SNAPSHOT.jar'
$keyFile = Join-Path $projectRoot 'backend/.env.local'

function Invoke-Step([string]$Label, [scriptblock]$Operation) {
    Write-Host ('  > ' + $Label) -ForegroundColor Cyan
    if (-not $DryRun) { & $Operation }
}

# Existing scripts use exit; run them in a child shell so the menu stays alive.
function Invoke-ProjectScript([string]$Name, [string[]]$ScriptArguments = @()) {
    & $powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot $Name) @ScriptArguments
    if ($LASTEXITCODE -ne 0) { throw ($Name + ' 执行失败，请查看上方错误。') }
}

function Build-Backend {
    Invoke-Step '编译并测试后端' {
        Push-Location (Join-Path $projectRoot 'backend')
        try {
            if (Get-Command mvn -ErrorAction SilentlyContinue) { & mvn -q -B -pl offerpilot-bootstrap -am package }
            else { & './mvnw.cmd' -q -B -pl offerpilot-bootstrap -am package }
            if ($LASTEXITCODE -ne 0) { throw '后端构建失败。' }
        } finally { Pop-Location }
    }
}

function Start-Dependencies {
    Invoke-Step '启动 PostgreSQL / Redis / MinIO / jobs-mcp，等待依赖就绪' {
        if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { throw '未找到 docker，请安装并打开 Docker Desktop，再重试。' }
        if (-not (Test-Path -LiteralPath $keyFile)) { throw '缺少 backend/.env.local，请先选择「配置 DeepSeek」。' }
        & docker compose --env-file $keyFile -f (Join-Path $projectRoot 'deploy/docker-compose.yml') up -d --build --wait postgres redis minio jobs-mcp
        if ($LASTEXITCODE -ne 0) { throw '依赖启动失败，请检查 Docker Desktop、端口与数据库配置。' }
    }
}

function Show-Status {
    foreach ($endpoint in @(
        @{Name='前端'; Url='http://localhost:8080'},
        @{Name='后端健康检查'; Url='http://127.0.0.1:8081/actuator/health'},
        @{Name='企业岗位服务'; Url='http://127.0.0.1:7001/health'}
    )) {
        try {
            $response = Invoke-WebRequest -UseBasicParsing -Uri $endpoint.Url -TimeoutSec 3
            $healthy = $response.StatusCode -eq 200
            if ($endpoint.Name -eq '后端健康检查') {
                # Windows PowerShell 5.1 may expose actuator's vendor JSON as bytes.
                $content = $response.Content
                if ($content -is [byte[]]) { $content = [Text.Encoding]::UTF8.GetString($content) }
                $healthy = $healthy -and (($content | ConvertFrom-Json).status -eq 'UP')
            }
            if (-not $healthy) { throw 'Not healthy' }
            Write-Host ('  [在线] ' + $endpoint.Name + '  ' + $endpoint.Url) -ForegroundColor Green
        } catch { Write-Host ('  [未就绪] ' + $endpoint.Name + '  ' + $endpoint.Url) -ForegroundColor Yellow }
    }
    if (Get-Command docker -ErrorAction SilentlyContinue) {
        if (Test-Path -LiteralPath $keyFile) {
            & docker compose --env-file $keyFile -f (Join-Path $projectRoot 'deploy/docker-compose.yml') ps
            if ($LASTEXITCODE -ne 0) { Write-Host '  Docker 状态读取失败，请根据上方错误检查环境配置或 Docker Desktop。' -ForegroundColor Yellow }
        } else { Write-Host '  缺少 backend/.env.local，请先选择「配置 DeepSeek」。' -ForegroundColor Yellow }
    } else { Write-Host '  Docker：未找到命令' -ForegroundColor Yellow }
    Write-Host '  健康检查不验证模型 Key；请在聊天页发送消息验证。' -ForegroundColor DarkGray
}

function Invoke-Action([string]$Selected) {
    switch ($Selected) {
        'start' {
            Start-Dependencies
            Build-Backend
            Invoke-Step '启动后端（dev / 8081）' { Invoke-ProjectScript 'backend-preview.ps1' @('-Action','start') }
            Invoke-Step '构建并更新前端' { Invoke-ProjectScript 'prepare.ps1' }
            Invoke-Step '启动前端（8080）' { Invoke-ProjectScript 'control.ps1' @('-Action','start') }
        }
        'stop' {
            Invoke-Step '停止本入口管理的后端' { Invoke-ProjectScript 'backend-preview.ps1' @('-Action','stop') }
            Invoke-Step '停止本项目 Nginx' { Invoke-ProjectScript 'control.ps1' @('-Action','stop') }
            Write-Host '  数据库容器继续运行，数据保留。' -ForegroundColor DarkGray
        }
        'status' { Invoke-Step '检查服务状态' { Show-Status } }
        'start-frontend' { Invoke-Step '启动前端' { Invoke-ProjectScript 'control.ps1' @('-Action','start') } }
        'stop-frontend' { Invoke-Step '停止前端' { Invoke-ProjectScript 'control.ps1' @('-Action','stop') } }
        'update-frontend' {
            Invoke-Step '构建并更新前端' { Invoke-ProjectScript 'prepare.ps1' }
            Invoke-Step '重载前端' { Invoke-ProjectScript 'control.ps1' @('-Action','reload') }
        }
        'start-backend' {
            Build-Backend
            Invoke-Step '启动后端（先确保数据库已启动）' { Invoke-ProjectScript 'backend-preview.ps1' @('-Action','start') }
        }
        'restart-backend' {
            # Build first; a failed build must not stop the existing server.
            Build-Backend
            Invoke-Step '停止本入口管理的后端' { Invoke-ProjectScript 'backend-preview.ps1' @('-Action','stop') }
            Invoke-Step '启动新版后端' { Invoke-ProjectScript 'backend-preview.ps1' @('-Action','start') }
        }
        'package' { Invoke-Step '生成前端分发包' { Invoke-ProjectScript 'package.ps1' } }
        'config' {
            Invoke-Step '打开 backend/.env.local（Key 不会打印到控制台）' {
                if (-not (Test-Path -LiteralPath $keyFile)) {
                    Copy-Item -LiteralPath (Join-Path $projectRoot 'backend/.env.example') -Destination $keyFile
                }
                Start-Process -FilePath notepad.exe -ArgumentList ('"' + $keyFile + '"')
            }
            Write-Host '  将 sk-not-configured 替换为 Key，保存后选择「重建并重启后端」。' -ForegroundColor DarkGray
        }
        'logs' {
            Invoke-Step '打开后端日志' {
                $logFile = 'D:\OfferPilot\backend-preview.log'
                if (-not (Test-Path -LiteralPath $logFile)) { throw '还没有后端日志，请先启动后端。' }
                Start-Process -FilePath notepad.exe -ArgumentList ('"' + $logFile + '"')
            }
        }
    }
}

Push-Location $projectRoot
try {
    if ($Action -ne 'menu') { Invoke-Action $Action; exit 0 }
    $choices = @{'1'='start'; '2'='stop'; '3'='status'; '4'='update-frontend'; '5'='restart-backend'; '6'='start-frontend'; '7'='package'; '8'='config'; '9'='logs'}
    do {
        if (-not $DryRun) { Clear-Host }
        Write-Host ''
        Write-Host '  +--------------------------------------------------+' -ForegroundColor Blue
        Write-Host '  |                  OFFERPILOT                      |' -ForegroundColor Cyan
        Write-Host '  |              职航 · 项目控制台                   |' -ForegroundColor White
        Write-Host '  +--------------------------------------------------+' -ForegroundColor Blue
        Write-Host ''
        Write-Host '    日常使用' -ForegroundColor Cyan
        Write-Host '    [1] 启动全部            [2] 停止前后端'
        Write-Host '    [3] 查看运行状态'
        Write-Host ''
        Write-Host '    开发与分发' -ForegroundColor Cyan
        Write-Host '    [4] 更新前端            [5] 重建并重启后端'
        Write-Host '    [6] 仅启动前端          [7] 打包前端'
        Write-Host ''
        Write-Host '    配置与排查' -ForegroundColor Cyan
        Write-Host '    [8] 配置 DeepSeek       [9] 查看后端日志'
        Write-Host ''
        Write-Host '    [0] 退出菜单（服务继续运行）' -ForegroundColor DarkGray
        Write-Host '    前端 localhost:8080  /  后端 localhost:8081' -ForegroundColor DarkGray
        Write-Host ''
        if ($DryRun) { break }
        $selection = Read-Host '  请选择'
        if ($selection -eq '0') { break }
        if (-not $choices.ContainsKey($selection)) { continue }
        try {
            Invoke-Action $choices[$selection]
            Write-Host "`n  操作结束。" -ForegroundColor Green
        } catch { Write-Host ("`n  " + $_.Exception.Message) -ForegroundColor Red }
        [void](Read-Host '  按 Enter 返回菜单')
    } while ($true)
} finally { Pop-Location }
