param([ValidateSet('start','stop','reload')][string]$Action = 'start', [string]$RuntimeRoot)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'runtime-root.ps1')
$runtimeRoot = Resolve-OfferPilotRuntimeRoot $RuntimeRoot
$runtimeDirectory = Join-Path $runtimeRoot 'nginx-1.31.5'
$executable = Join-Path $runtimeDirectory 'nginx.exe'
if (-not (Test-Path -LiteralPath $executable)) {
    if ($Action -ne 'start') { Write-Host 'This project Nginx is not installed.'; exit 0 }
    & (Join-Path $PSScriptRoot 'prepare.ps1') -RuntimeRoot $runtimeRoot
}
$pidFile = Join-Path $runtimeDirectory 'logs/nginx.pid'
$running = $false
if (Test-Path -LiteralPath $pidFile) {
    $nginxProcessId = 0
    $pidText = [string](Get-Content -LiteralPath $pidFile -Raw)
    if (-not [string]::IsNullOrWhiteSpace($pidText) -and [int]::TryParse($pidText.Trim(), [ref]$nginxProcessId)) {
        $ownedProcess = Get-Process -Id $nginxProcessId -ErrorAction SilentlyContinue
        if ($ownedProcess -and $ownedProcess.Path) {
            $running = [IO.Path]::GetFullPath($ownedProcess.Path) -eq [IO.Path]::GetFullPath($executable)
        }
    }
}
Push-Location $runtimeDirectory
try {
    if ($Action -eq 'stop') {
        if ($running) { & './nginx.exe' -s quit; if ($LASTEXITCODE -ne 0) { throw 'Nginx stop failed.' } }
        else { Write-Host 'This project Nginx is not running.' }
        exit 0
    }
    & './nginx.exe' -t
    if ($LASTEXITCODE -ne 0) { throw 'Nginx configuration check failed.' }
    if ($running) {
        if ($Action -eq 'reload') { & './nginx.exe' -s reload; if ($LASTEXITCODE -ne 0) { throw 'Reload failed.' } }
        Write-Host 'Project Nginx is running: http://localhost:8080'
    } else {
        $started = Start-Process -FilePath $executable -WorkingDirectory $runtimeDirectory -WindowStyle Hidden -PassThru
        Start-Sleep -Milliseconds 700
        if ($started.HasExited -and $started.ExitCode -ne 0) { throw 'Nginx could not start. Check logs/error.log and port 8080.' }
        Write-Host 'Started: http://localhost:8080'
    }
} finally { Pop-Location }
