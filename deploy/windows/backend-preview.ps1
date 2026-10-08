param(
    [ValidateSet('start','stop')][string]$Action = 'start',
    [string]$JarPath
)
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$runtimeRoot = 'D:\OfferPilot'
$statePath = Join-Path $runtimeRoot 'backend-preview.json'
function Get-OwnedBackend {
    if (-not (Test-Path -LiteralPath $statePath)) { return $null }
    try {
        $saved = Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
        $candidate = Get-Process -Id $saved.processId -ErrorAction SilentlyContinue
        if (-not $candidate -or $candidate.Path -ne $saved.executable -or
            $candidate.StartTime.ToUniversalTime().ToString('o') -ne $saved.startedAt) { return $null }
        if ([IO.Path]::GetFileName($candidate.Path) -notin @('java.exe', 'javaw.exe')) { return $null }
        # Pin the process handle before stopping; a recycled PID must not select a new process.
        [void]$candidate.Handle
        $processInfo = Get-CimInstance Win32_Process -Filter ('ProcessId = ' + $candidate.Id)
        if (-not $processInfo.CommandLine -or $processInfo.CommandLine -notmatch '(?:^|\s)-jar\s+(?:"([^"]+)"|(\S+))(?=\s|$)') { return $null }
        $commandJar = if ($Matches[1]) { $Matches[1] } else { $Matches[2] }
        if (-not [IO.Path]::IsPathRooted($commandJar)) { return $null }
        $commandJar = [IO.Path]::GetFullPath($commandJar)
        if ($saved.projectRoot) {
            if ([IO.Path]::GetFullPath($saved.projectRoot) -ne $projectRoot -or
                -not $saved.jarPath -or $commandJar -ne [IO.Path]::GetFullPath($saved.jarPath)) { return $null }
            Assert-BackendJarPath $commandJar
        } else {
            # Older metadata has no project root: adopt only this checkout's exact legacy JAR.
            $legacyJar = [IO.Path]::GetFullPath((Join-Path $projectRoot 'backend/offerpilot-bootstrap/target/offerpilot-bootstrap-0.1.0-SNAPSHOT.jar'))
            if ($commandJar -ne $legacyJar) { return $null }
        }
        return $candidate
    } catch { Write-Host 'Previous preview process could not be verified; preserving it.' }
    return $null
}

function Assert-BackendJarPath([string]$Path) {
    $backendRoot = [IO.Path]::GetFullPath((Join-Path $projectRoot 'backend')) + [IO.Path]::DirectorySeparatorChar
    if (-not [IO.Path]::IsPathRooted($Path) -or
        -not ([IO.Path]::GetFullPath($Path)).StartsWith($backendRoot, [StringComparison]::OrdinalIgnoreCase) -or
        [IO.Path]::GetFileName($Path) -ne 'offerpilot-bootstrap-0.1.0-SNAPSHOT.jar') {
        throw 'Backend JAR must belong to this project backend directory.'
    }
}

function Invoke-BackendPreview {
    $owned = Get-OwnedBackend
    if ($Action -eq 'stop') {
        if ($owned) {
            Stop-Process -InputObject $owned -ErrorAction Stop
            if (-not $owned.WaitForExit(10000)) { throw 'Preview backend did not stop; refusing to start another instance.' }
            Write-Host 'Stopped this preview backend.'
        } else { Write-Host 'No owned preview backend to stop.' }
        return
    }
    if ($owned) { Write-Host 'Preview backend already running on port 8081.'; return }
    $probe = New-Object Net.Sockets.TcpClient
    try {
        $connection = $probe.ConnectAsync('127.0.0.1', 8081)
        if ($connection.Wait(1000) -and $probe.Connected) {
            Write-Host 'Port 8081 is already in use; preserving the existing backend. See connection settings in the frontend.'
            return
        }
    } catch { } finally { $probe.Dispose() }
    $jarPath = if ($JarPath) { [IO.Path]::GetFullPath($JarPath) } else {
        Join-Path $projectRoot 'backend/offerpilot-bootstrap/target/offerpilot-bootstrap-0.1.0-SNAPSHOT.jar'
    }
    Assert-BackendJarPath $jarPath
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
    @{ processId = $backendProcess.Id; startedAt = $backendProcess.StartTime.ToUniversalTime().ToString('o'); executable = $javaExecutable; projectRoot = $projectRoot; jarPath = $jarPath } | ConvertTo-Json | Set-Content -LiteralPath $statePath -Encoding utf8
    for ($attempt = 0; $attempt -lt 45; $attempt++) {
        if ($backendProcess.HasExited) { throw 'Preview backend failed. Read D:\OfferPilot\backend-preview.log.' }
        try {
            $health = Invoke-RestMethod -Uri 'http://127.0.0.1:8081/actuator/health' -TimeoutSec 1
            if ($health.status -eq 'UP') {
                Write-Host 'Backend is ready. Open http://localhost:8080/chat. Configure DEEPSEEK_API_KEY in backend/.env.local and restart to enable real model replies.'
                return
            }
        } catch { }
        Start-Sleep -Milliseconds 500
    }
    Write-Host 'Backend is still starting. Check D:\OfferPilot\backend-preview.log and the frontend connection settings.'
}

if ($MyInvocation.InvocationName -ne '.') { Invoke-BackendPreview }
