# Run with Windows PowerShell 5.1; no Pester or real service control required.
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '../console.ps1')
. (Join-Path $PSScriptRoot '../backend-preview.ps1')
$fixtureExecutable = 'C:\fixture-jdk\bin\java.exe'
$fixtureStartedAt = [DateTime]::Parse('2026-10-08T12:00:00Z').ToUniversalTime()
$legacyJar = Join-Path $projectRoot 'backend/offerpilot-bootstrap/target/offerpilot-bootstrap-0.1.0-SNAPSHOT.jar'
$fixtureJar = Join-Path $projectRoot 'backend/target/preview-builds/test/backend/offerpilot-bootstrap/target/offerpilot-bootstrap-0.1.0-SNAPSHOT.jar'
$fixtureProcess = [pscustomobject]@{ Id = 700001; Path = $fixtureExecutable; StartTime = $fixtureStartedAt; Handle = [IntPtr]::Zero }
$fixtureProcess | Add-Member ScriptMethod WaitForExit { param($Timeout) return $script:exitConfirmed }

# All process operations are intercepted. The fixture PID is never used with the OS.
function Get-Process { param($Id, $ErrorAction) return $script:candidate }
function Get-CimInstance { param($ClassName, $Filter) return [pscustomobject]@{ CommandLine = $script:commandLine } }
function Test-Path {
    param($LiteralPath)
    if ($LiteralPath -eq $statePath) { return $script:stateExists }
    return Microsoft.PowerShell.Management\Test-Path -LiteralPath $LiteralPath
}
function Get-Content {
    param($LiteralPath, [switch]$Raw)
    if ($LiteralPath -eq $statePath) { return $script:stateText }
    return Microsoft.PowerShell.Management\Get-Content -LiteralPath $LiteralPath -Raw:$Raw
}
function Stop-Process { param($InputObject, $ErrorAction) $script:stopped += $InputObject.Id }
function Start-Process { throw 'Unexpected process launch in regression test.' }
function Invoke-ProjectScript {
    param($Name, $ScriptArguments)
    $script:steps += ($Name + ':' + ($ScriptArguments -join '|'))
    if ($script:stopFails -and $ScriptArguments[1] -eq 'stop') { throw 'Stop failed.' }
}
function Reset-Fixture {
    $script:stateExists = $true
    $script:candidate = $fixtureProcess
    $script:commandLine = 'java -jar "' + $fixtureJar + '" --spring.profiles.active=dev'
    $script:saved = @{ processId = $fixtureProcess.Id; executable = $fixtureExecutable; startedAt = $fixtureStartedAt.ToString('o'); projectRoot = $projectRoot; jarPath = $fixtureJar }
    $script:stateText = $script:saved | ConvertTo-Json
    $script:stopped = @()
    $script:steps = @()
    $script:exitConfirmed = $true
    $script:stopFails = $false
}
function Assert-True($Condition, [string]$Message) { if (-not $Condition) { throw $Message } }
function Assert-Throws([scriptblock]$Operation) {
    $threw = $false
    try { & $Operation | Out-Null } catch { $threw = $true }
    Assert-True $threw 'Expected operation to fail.'
}
$passed = 0
function Test-Case([string]$Name, [scriptblock]$Operation) {
    Reset-Fixture
    & $Operation
    Write-Host ('PASS ' + $Name)
    $script:passed++
}

Test-Case 'matching managed process stops and waits for exit' {
    $Action = 'stop'
    Invoke-BackendPreview
    Assert-True ($script:stopped.Count -eq 1 -and $script:stopped[0] -eq 700001) 'Owned backend was not stopped.'
}
Test-Case 'another checkout sharing runtime metadata is preserved' {
    $script:saved.projectRoot = 'E:\other-checkout'
    $script:stateText = $script:saved | ConvertTo-Json
    $Action = 'stop'
    Invoke-BackendPreview
    Assert-True ($script:stopped.Count -eq 0) 'Another checkout was stopped.'
}
Test-Case 'reused PID with a different start time is preserved' {
    $script:saved.startedAt = '2026-10-07T12:00:00.0000000Z'
    $script:stateText = $script:saved | ConvertTo-Json
    Assert-True ($null -eq (Get-OwnedBackend)) 'Reused PID was adopted.'
}
Test-Case 'changed Java executable is preserved' {
    $script:saved.executable = 'C:\other-jdk\bin\java.exe'
    $script:stateText = $script:saved | ConvertTo-Json
    Assert-True ($null -eq (Get-OwnedBackend)) 'Different executable was adopted.'
}
Test-Case 'same basename in another checkout is preserved' {
    $script:commandLine = 'java -jar E:\other-checkout\backend\offerpilot-bootstrap\target\offerpilot-bootstrap-0.1.0-SNAPSHOT.jar'
    Assert-True ($null -eq (Get-OwnedBackend)) 'Other JAR was adopted.'
}
Test-Case 'unavailable command line is preserved' {
    $script:commandLine = $null
    Assert-True ($null -eq (Get-OwnedBackend)) 'Unverifiable process was adopted.'
}
Test-Case 'missing process is harmless' {
    $script:candidate = $null
    Assert-True ($null -eq (Get-OwnedBackend)) 'Missing process was adopted.'
}
Test-Case 'missing metadata is harmless' {
    $script:stateExists = $false
    Assert-True ($null -eq (Get-OwnedBackend)) 'Process without state was adopted.'
}
Test-Case 'malformed metadata preserves all processes' {
    $script:stateText = 'invalid json'
    Assert-True ($null -eq (Get-OwnedBackend)) 'Invalid metadata was accepted.'
}
Test-Case 'legacy metadata adopts only this checkout exact JAR' {
    $script:saved = @{ processId = 700001; executable = $fixtureExecutable; startedAt = $fixtureStartedAt.ToString('o') }
    $script:stateText = $script:saved | ConvertTo-Json
    $script:commandLine = 'java -jar "' + $legacyJar + '"'
    Assert-True ($null -ne (Get-OwnedBackend)) 'Legacy owned backend was not recognized.'
    $script:commandLine = 'java -jar E:\other-checkout\offerpilot-bootstrap-0.1.0-SNAPSHOT.jar'
    Assert-True ($null -eq (Get-OwnedBackend)) 'Other legacy checkout was adopted.'
}
Test-Case 'relative or escaped JAR paths are rejected' {
    Assert-Throws { Assert-BackendJarPath 'backend\offerpilot-bootstrap-0.1.0-SNAPSHOT.jar' }
    Assert-Throws { Assert-BackendJarPath (Join-Path $projectRoot 'backend/../../offerpilot-bootstrap-0.1.0-SNAPSHOT.jar') }
}
Test-Case 'owned process already running is not relaunched' {
    $Action = 'start'
    Invoke-BackendPreview
    Assert-True ($script:stopped.Count -eq 0) 'Start stopped an owned process.'
}
Test-Case 'a stop timeout fails the operation' {
    $Action = 'stop'
    $script:exitConfirmed = $false
    Assert-Throws { Invoke-BackendPreview }
}
Test-Case 'an occupied port is preserved without adopting or launching' {
    $script:stateExists = $false
    $Action = 'start'
    function New-Object {
        param($TypeName)
        if ($TypeName -ne 'Net.Sockets.TcpClient') { throw 'Unexpected object in port test.' }
        $probe = [pscustomobject]@{ Connected = $true }
        $probe | Add-Member ScriptMethod ConnectAsync {
            param($Address, $Port)
            $connection = [pscustomobject]@{}
            $connection | Add-Member ScriptMethod Wait { param($Timeout) return $true }
            return $connection
        }
        $probe | Add-Member ScriptMethod Dispose {}
        return $probe
    }
    Invoke-BackendPreview
    Assert-True ($script:stopped.Count -eq 0) 'Occupied port owner was stopped.'
}
Test-Case 'successful publication stops then passes exact built JAR' {
    Publish-Backend $fixtureJar
    Assert-True ($script:steps.Count -eq 2) 'Unexpected publication steps.'
    Assert-True ($script:steps[0] -eq 'backend-preview.ps1:-Action|stop') 'Stop was not first.'
    Assert-True ($script:steps[1] -eq ('backend-preview.ps1:-Action|start|-JarPath|' + $fixtureJar)) 'Wrong JAR was published.'
}
Test-Case 'stop failure prevents replacement startup' {
    $script:stopFails = $true
    Assert-Throws { Publish-Backend $fixtureJar }
    Assert-True ($script:steps.Count -eq 1) 'New process was started after stop failure.'
}
Test-Case 'build failure never stops or starts a backend' {
    function Build-Backend { throw 'Fixture build failure.' }
    foreach ($selected in @('start', 'start-backend', 'restart-backend')) {
        function Start-Dependencies {}
        Assert-Throws { Invoke-Action $selected }
    }
    Assert-True ($script:steps.Count -eq 0 -and $script:stopped.Count -eq 0) 'Failed build changed running services.'
}
Test-Case 'native Maven failure aborts publication without a clean or retry' {
    $fixtureBuild = Join-Path $projectRoot ('backend/target/deployment-tests/' + [Guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $fixtureBuild -Force | Out-Null
    $fixtureCommand = @'
@echo off
echo %*>>arguments.txt
exit /b 1
'@
    Set-Content -LiteralPath (Join-Path $fixtureBuild 'mvnw.cmd') -Value $fixtureCommand -Encoding ASCII
    function New-BackendBuildDirectory { return $fixtureBuild }
    $originalLocation = (Get-Location).Path
    Assert-Throws { Invoke-Action 'restart-backend' }
    $invocations = @(Get-Content -LiteralPath (Join-Path $fixtureBuild 'arguments.txt'))
    Assert-True ($invocations.Count -eq 1 -and $invocations[0] -eq '-q -B -pl offerpilot-bootstrap -am package') 'Maven retried or requested clean.'
    Assert-True ((Get-Location).Path -eq $originalLocation) 'Build failure did not restore the working directory.'
    Assert-True ($script:steps.Count -eq 0 -and $script:stopped.Count -eq 0) 'Native build failure changed running services.'
}
Test-Case 'successful native build returns only its fresh JAR path' {
    $fixtureBuild = Join-Path $projectRoot ('backend/target/deployment-tests/' + [Guid]::NewGuid().ToString('N'))
    $expectedJar = Join-Path $fixtureBuild 'offerpilot-bootstrap/target/offerpilot-bootstrap-0.1.0-SNAPSHOT.jar'
    New-Item -ItemType Directory -Path (Split-Path $expectedJar) -Force | Out-Null
    Set-Content -LiteralPath $expectedJar -Value 'fixture' -Encoding ASCII
    Set-Content -LiteralPath (Join-Path $fixtureBuild 'mvnw.cmd') -Value "@echo off`r`necho Build fixture log`r`nexit /b 0" -Encoding ASCII
    function New-BackendBuildDirectory { return $fixtureBuild }
    $result = @(Build-Backend)
    Assert-True ($result.Count -eq 1 -and $result[0] -eq $expectedJar) 'Build output contaminated the JAR path.'
}
Test-Case 'dry run performs no process or build work' {
    $DryRun = $true
    function New-BackendBuildDirectory { throw 'Dry run created build directory.' }
    foreach ($selected in @('start', 'start-backend', 'restart-backend', 'stop')) { Invoke-Action $selected }
    Assert-True ($script:steps.Count -eq 0 -and $script:stopped.Count -eq 0) 'Dry run changed services.'
}
Write-Host ("Passed $passed deployment lifecycle regression cases.")
