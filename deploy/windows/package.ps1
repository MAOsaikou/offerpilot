param([string]$OutputDirectory, [switch]$SkipBuild, [string]$RuntimeRoot)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'runtime-root.ps1')
$runtimeRoot = Resolve-OfferPilotRuntimeRoot $RuntimeRoot
$runtimeDirectory = Join-Path $runtimeRoot 'nginx-1.31.5'
if (-not $OutputDirectory) { $OutputDirectory = Join-Path $runtimeRoot 'releases' }
$stamp = Get-Date -Format 'yyyyMMdd-HHmm'
$packageDirectory = Join-Path $OutputDirectory ('OfferPilot-Frontend-' + $stamp)
$zipPath = Join-Path $OutputDirectory ('OfferPilot-Frontend-' + $stamp + '.zip')
if (-not $SkipBuild) { & (Join-Path $PSScriptRoot 'prepare.ps1') -RuntimeRoot $runtimeRoot }
if (-not (Test-Path -LiteralPath (Join-Path $runtimeDirectory 'nginx.exe'))) { throw 'Nginx runtime missing. Run prepare.ps1 first.' }
if (-not (Test-Path -LiteralPath (Join-Path $runtimeDirectory 'html/index.html'))) { throw 'Frontend build missing. Run prepare.ps1 first.' }
New-Item -ItemType Directory -Path $packageDirectory -Force | Out-Null
New-Item -ItemType Directory -Path (Join-Path $packageDirectory 'conf') -Force | Out-Null
New-Item -ItemType Directory -Path (Join-Path $packageDirectory 'html') -Force | Out-Null
New-Item -ItemType Directory -Path (Join-Path $packageDirectory 'logs') -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $runtimeDirectory 'nginx.exe') -Destination $packageDirectory -Force
Copy-Item -LiteralPath (Join-Path $runtimeDirectory 'conf/mime.types') -Destination (Join-Path $packageDirectory 'conf/mime.types') -Force
Copy-Item -LiteralPath (Join-Path $runtimeDirectory 'conf/nginx.conf') -Destination (Join-Path $packageDirectory 'conf/nginx.conf') -Force
Get-ChildItem -LiteralPath (Join-Path $runtimeDirectory 'html') | ForEach-Object { Copy-Item -LiteralPath $_.FullName -Destination (Join-Path $packageDirectory 'html') -Recurse -Force }
Set-Content -LiteralPath (Join-Path $packageDirectory '启动前端.cmd') -Encoding utf8 -Value '@echo off`r`ncd /d "%~dp0"`r`nnginx.exe -t`r`nif errorlevel 1 (pause & exit /b 1)`r`nstart "OfferPilot" /min nginx.exe`r`necho OfferPilot is running at http://localhost:8080`r`npause'
Set-Content -LiteralPath (Join-Path $packageDirectory '停止前端.cmd') -Encoding utf8 -Value '@echo off`r`ncd /d "%~dp0"`r`nnginx.exe -s quit`r`necho OfferPilot has stopped.`r`npause'
Set-Content -LiteralPath (Join-Path $packageDirectory 'README.txt') -Encoding utf8 -Value @'
OfferPilot 职航 · Windows 前端包

双击「启动前端.cmd」，再打开 http://localhost:8080。停止时双击「停止前端.cmd」。
这个包包含前端生产文件和 Nginx。AI 对话需要 127.0.0.1:8081 的后端，Nginx 会自动代理 /api/。
业务数据保存在浏览器本地。包内不包含 Java、数据库、模型密钥或后端 JAR。
'@
Compress-Archive -LiteralPath $packageDirectory -DestinationPath $zipPath -CompressionLevel Optimal -Force
Write-Host ('Package directory: ' + $packageDirectory)
Write-Host ('Package zip: ' + $zipPath)
