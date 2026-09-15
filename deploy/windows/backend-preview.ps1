param([ValidateSet('start','stop')][string]$Action = 'start')
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$runtimeRoot = 'D:\OfferPilot'
$statePath = Join-Path $runtimeRoot 'backend-preview.json'
$owned = $null
if (Test-Path -LiteralPath $statePath) {
    try {
        $saved = Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
        $candidate = Get-Process -Id $saved.processId -ErrorAction SilentlyContinue
        if ($candidate -and $candidate.Path -eq $saved.executable -and $candidate.StartTime.ToUniversalTime().ToString('o') -eq $saved.startedAt) {
            $owned = $candidate
        }
    } catch { Write-Host 'Previous preview process is no longer available.' }
}
if ($Action -eq 'stop') {
    if ($owned) { Stop-Process -Id $owned.Id; Write-Host 'Stopped this preview backend.' }
    else { Write-Host 'No owned preview backend to stop.' }
    exit 0
}
if ($owned) { Write-Host 'Preview backend already running on port 8081.'; exit 0 }
$probe = New-Object Net.Sockets.TcpClient
try {
    $connection = $probe.ConnectAsync('127.0.0.1', 8081)
    if ($connection.Wait(1000) -and $probe.Connected) {
        Write-Host 'Port 8081 is already in use; preserving the existing backend. See connection settings in the frontend.'
        exit 0
    }
} catch { } finally { $probe.Dispose() }
$jarPath = Join-Path $projectRoot 'backend/offerpilot-bootstrap/target/offerpilot-bootstrap-0.1.0-SNAPSHOT.jar'
if (-not (Test-Path -LiteralPath $jarPath)) {
    throw 'Backend JAR missing. Run mvn -q -B -pl offerpilot-bootstrap -am package from backend first.'
}
# The project requires JDK 17+. JAVA_HOME and PATH may point at different versions
# (on Windows the machine-level PATH is searched before the user-level one), so try
# both candidates and use the first one whose version satisfies the requirement.
$javaCandidates = @()
if ($env:JAVA_HOME) { $javaCandidates += (Join-Path $env:JAVA_HOME 'bin\java.exe') }
$javaOnPath = Get-Command java -ErrorAction SilentlyContinue
if ($javaOnPath) { $javaCandidates += $javaOnPath.Source }
$javaExecutable = $null
foreach ($candidate in $javaCandidates) {
    if (-not (Test-Path -LiteralPath $candidate)) { continue }
    # java -version writes to stderr; with ErrorActionPreference=Stop the 2>&1
    # redirect would throw, so relax the preference just for this probe.
    $previousErrorAction = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $versionLine = (& $candidate -version 2>&1 | Select-Object -First 1 | Out-String)
    } finally {
        $ErrorActionPreference = $previousErrorAction
    }
    if ($versionLine -match 'version "(?<major>\d+)' -and [int]$Matches['major'] -ge 17) {
        $javaExecutable = $candidate
        Write-Host ("Using Java: " + $candidate + " (" + $versionLine.Trim() + ")")
        break
    }
}
if (-not $javaExecutable) {
    throw 'Java 17+ not found. Install JDK 17, or point JAVA_HOME at it, then retry.'
}
New-Item -ItemType Directory -Path $runtimeRoot -Force | Out-Null
$arguments = @('-jar', ('"' + $jarPath + '"'), '--spring.profiles.active=dev', '--server.address=127.0.0.1')
$backendProcess = Start-Process -FilePath $javaExecutable -ArgumentList $arguments -WorkingDirectory $projectRoot -WindowStyle Hidden -RedirectStandardOutput (Join-Path $runtimeRoot 'backend-preview.log') -RedirectStandardError (Join-Path $runtimeRoot 'backend-preview-error.log') -PassThru
@{ processId = $backendProcess.Id; startedAt = $backendProcess.StartTime.ToUniversalTime().ToString('o'); executable = $javaExecutable } | ConvertTo-Json | Set-Content -LiteralPath $statePath -Encoding utf8
for ($attempt = 0; $attempt -lt 45; $attempt++) {
    if ($backendProcess.HasExited) { throw 'Preview backend failed. Read D:\OfferPilot\backend-preview.log.' }
    try {
        $health = Invoke-RestMethod -Uri 'http://127.0.0.1:8081/actuator/health' -TimeoutSec 1
        if ($health.status -eq 'UP') {
            Write-Host 'Backend is ready. Open http://localhost:8080/chat. Configure DEEPSEEK_API_KEY in backend/.env.local and restart to enable real model replies.'
            exit 0
        }
    } catch { }
    Start-Sleep -Milliseconds 500
}
Write-Host 'Backend is still starting. Check D:\OfferPilot\backend-preview.log and the frontend connection settings.'
