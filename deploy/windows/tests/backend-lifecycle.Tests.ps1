param([string]$TestNamePattern = '*')
# Run with Windows PowerShell 5.1; no Pester or real service control required.
$ErrorActionPreference = 'Stop'
$defaultRuntimeRoot = [IO.Path]::GetFullPath('D:\OfferPilot')
. (Join-Path $PSScriptRoot '../console.ps1') -RuntimeRoot $defaultRuntimeRoot
. (Join-Path $PSScriptRoot '../backend-preview.ps1') -RuntimeRoot $defaultRuntimeRoot
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
    if ($Name -notlike $TestNamePattern) { return }
    Reset-Fixture
    $previousRuntimeRoot = $env:OFFERPILOT_RUNTIME_ROOT
    try {
        & $Operation
        Write-Host ('PASS ' + $Name)
        $script:passed++
    } finally { $env:OFFERPILOT_RUNTIME_ROOT = $previousRuntimeRoot }
}

function Assert-FrontendRuntimePaths([string]$Name, [string]$ExpectedRoot, [hashtable]$Parameters) {
    # Stop immediately after path initialization; never download, build or control Nginx.
    function Test-Path { param($LiteralPath) throw 'Intercepted frontend filesystem access.' }
    try {
        . (Join-Path $PSScriptRoot ('../' + $Name)) @Parameters
        throw 'Frontend script did not reach the filesystem interceptor.'
    } catch {
        Assert-True ($_.Exception.Message -eq 'Intercepted frontend filesystem access.') ('Unexpected frontend error: ' + $_.Exception.Message)
    }
    Assert-True ($runtimeRoot -eq $ExpectedRoot) ($Name + ' resolved the wrong runtime root.')
    $expectedNginx = Join-Path $ExpectedRoot 'nginx-1.31.5'
    if ($Name -eq 'prepare.ps1') {
        Assert-True ($NginxDirectory -eq $expectedNginx) 'Prepare used the wrong Nginx directory.'
    } else {
        Assert-True ($runtimeDirectory -eq $expectedNginx) ($Name + ' used the wrong Nginx directory.')
    }
    if ($Name -eq 'package.ps1') {
        $expectedOutput = if ($Parameters.ContainsKey('OutputDirectory')) { $Parameters.OutputDirectory } else { Join-Path $ExpectedRoot 'releases' }
        Assert-True ($OutputDirectory -eq $expectedOutput) 'Package used the wrong output directory.'
    }
}

Test-Case 'all scripts default to the original runtime root without an override' {
    $env:OFFERPILOT_RUNTIME_ROOT = $null
    . (Join-Path $PSScriptRoot '../console.ps1')
    Assert-True ($runtimeRoot -eq $defaultRuntimeRoot) 'Console default changed.'
    . (Join-Path $PSScriptRoot '../backend-preview.ps1')
    Assert-True ($runtimeRoot -eq $defaultRuntimeRoot) 'Backend default changed.'
    Assert-True ($statePath -eq (Join-Path $defaultRuntimeRoot 'backend-preview.json')) 'Default state path changed.'
    Assert-True ($logPath -eq (Join-Path $defaultRuntimeRoot 'backend-preview.log')) 'Default log path changed.'
    Assert-FrontendRuntimePaths 'prepare.ps1' $defaultRuntimeRoot @{ SkipBuild = $true }
    Assert-FrontendRuntimePaths 'control.ps1' $defaultRuntimeRoot @{}
    Assert-FrontendRuntimePaths 'package.ps1' $defaultRuntimeRoot @{ SkipBuild = $true }
}
Test-Case 'environment runtime root controls every script and derived directory' {
    $customRoot = Join-Path $projectRoot 'backend/target/deployment-tests/runtime with spaces/../environment-runtime'
    $expectedRoot = [IO.Path]::GetFullPath($customRoot)
    $env:OFFERPILOT_RUNTIME_ROOT = $customRoot
    . (Join-Path $PSScriptRoot '../console.ps1')
    Assert-True ($runtimeRoot -eq $expectedRoot) 'Console ignored the environment runtime root.'
    . (Join-Path $PSScriptRoot '../backend-preview.ps1')
    Assert-True ($runtimeRoot -eq $expectedRoot) 'Backend ignored the environment runtime root.'
    Assert-True ($statePath -eq (Join-Path $expectedRoot 'backend-preview.json')) 'State escaped the custom root.'
    Assert-True ($logPath -eq (Join-Path $expectedRoot 'backend-preview.log')) 'Log escaped the custom root.'
    Assert-True ($errorLogPath -eq (Join-Path $expectedRoot 'backend-preview-error.log')) 'Error log escaped the custom root.'
    Assert-FrontendRuntimePaths 'prepare.ps1' $expectedRoot @{ SkipBuild = $true }
    Assert-FrontendRuntimePaths 'control.ps1' $expectedRoot @{}
    Assert-FrontendRuntimePaths 'package.ps1' $expectedRoot @{ SkipBuild = $true }
}
Test-Case 'explicit runtime root overrides the environment in every script' {
    $env:OFFERPILOT_RUNTIME_ROOT = Join-Path $projectRoot 'backend/target/deployment-tests/environment-runtime'
    $customRoot = Join-Path $projectRoot 'backend/target/deployment-tests/explicit runtime/.'
    $expectedRoot = [IO.Path]::GetFullPath($customRoot)
    . (Join-Path $PSScriptRoot '../console.ps1') -RuntimeRoot $customRoot
    Assert-True ($runtimeRoot -eq $expectedRoot) 'Console parameter did not override the environment.'
    . (Join-Path $PSScriptRoot '../backend-preview.ps1') -RuntimeRoot $customRoot
    Assert-True ($runtimeRoot -eq $expectedRoot) 'Backend parameter did not override the environment.'
    Assert-True ($statePath -eq (Join-Path $expectedRoot 'backend-preview.json')) 'State ignored the explicit root.'
    Assert-True ($logPath -eq (Join-Path $expectedRoot 'backend-preview.log')) 'Log ignored the explicit root.'
    Assert-FrontendRuntimePaths 'prepare.ps1' $expectedRoot @{ RuntimeRoot = $customRoot; SkipBuild = $true }
    Assert-FrontendRuntimePaths 'control.ps1' $expectedRoot @{ RuntimeRoot = $customRoot }
    Assert-FrontendRuntimePaths 'package.ps1' $expectedRoot @{ RuntimeRoot = $customRoot; SkipBuild = $true }
}
Test-Case 'empty environment falls back and trailing separators remain valid' {
    $env:OFFERPILOT_RUNTIME_ROOT = ''
    Assert-True ((Resolve-OfferPilotRuntimeRoot) -eq $defaultRuntimeRoot) 'Empty environment changed the default.'
    $customRoot = Join-Path $projectRoot 'backend/target/deployment-tests/runtime'
    $customRoot += [IO.Path]::DirectorySeparatorChar
    Assert-FrontendRuntimePaths 'prepare.ps1' ([IO.Path]::GetFullPath($customRoot)) @{ RuntimeRoot = $customRoot; SkipBuild = $true }
}
Test-Case 'package output directory remains independently overridable' {
    $customRoot = Join-Path $projectRoot 'backend/target/deployment-tests/runtime'
    $customOutput = Join-Path $projectRoot 'backend/target/deployment-tests/separate-releases'
    Assert-FrontendRuntimePaths 'package.ps1' $customRoot @{ RuntimeRoot = $customRoot; OutputDirectory = $customOutput; SkipBuild = $true }
}
Test-Case 'prepare containment errors name the resolved runtime root' {
    $customRoot = Join-Path $projectRoot 'backend/target/deployment-tests/runtime'
    $outsideNginx = Join-Path $projectRoot 'backend/target/deployment-tests/runtime-other/nginx-1.31.5'
    $message = $null
    try { . (Join-Path $PSScriptRoot '../prepare.ps1') -RuntimeRoot $customRoot -NginxDirectory $outsideNginx -SkipBuild }
    catch { $message = $_.Exception.Message }
    Assert-True ($message -eq "NginxDirectory must be inside $customRoot.") 'Containment error ignored the custom root.'
}
Test-Case 'console passes the custom runtime root through every child action' {
    $runtimeRoot = Join-Path $projectRoot 'backend/target/deployment-tests/menu runtime'
    function Start-Dependencies {}
    function Build-Backend { return $fixtureJar }
    foreach ($selected in @('start', 'stop', 'start-frontend', 'stop-frontend', 'update-frontend', 'start-backend', 'restart-backend', 'package')) {
        $script:steps = @()
        Invoke-Action $selected
        Assert-True ($script:steps.Count -gt 0) ($selected + ' invoked no child script.')
        foreach ($step in $script:steps) {
            $expectedSuffix = '-RuntimeRoot|' + $runtimeRoot
            Assert-True ($step.EndsWith(':' + $expectedSuffix) -or $step.EndsWith('|' + $expectedSuffix)) ('Child ignored runtime root: ' + $step)
        }
    }
}
Test-Case 'console opens the log beneath its custom runtime root' {
    $runtimeRoot = Join-Path $projectRoot 'backend/target/deployment-tests/menu runtime'
    $expectedLog = Join-Path $runtimeRoot 'backend-preview.log'
    function Test-Path {
        param($LiteralPath)
        Assert-True ($LiteralPath -eq $expectedLog) 'Console checked the wrong log path.'
        return $true
    }
    function Start-Process {
        param($FilePath, $ArgumentList)
        Assert-True ($FilePath -eq 'notepad.exe' -and $ArgumentList -eq ('"' + $expectedLog + '"')) 'Console opened the wrong log.'
        $script:steps += 'opened-log'
    }
    Invoke-Action 'logs'
    Assert-True ($script:steps.Count -eq 1) 'Console did not open the log.'
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
    Assert-True ($script:steps[0] -eq ('backend-preview.ps1:-Action|stop|-RuntimeRoot|' + $defaultRuntimeRoot)) 'Stop was not first or used the wrong root.'
    Assert-True ($script:steps[1] -eq ('backend-preview.ps1:-Action|start|-JarPath|' + $fixtureJar + '|-RuntimeRoot|' + $defaultRuntimeRoot)) 'Wrong JAR or runtime root was published.'
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
Test-Case 'native Maven saves errors and failure aborts publication without a clean or retry' {
    $fixtureBuild = Join-Path $projectRoot ('backend/target/deployment-tests/' + [Guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $fixtureBuild -Force | Out-Null
    $fixtureCommand = @'
@echo off
echo %*>>arguments.txt
for /L %%i in (1,1,12) do echo [ERROR] fixture build failure %%i 1>&2
exit /b 1
'@
    Set-Content -LiteralPath (Join-Path $fixtureBuild 'mvnw.cmd') -Value $fixtureCommand -Encoding ASCII
    function New-BackendBuildDirectory { return $fixtureBuild }
    $script:buildMessages = New-Object 'Collections.Generic.List[string]'
    function Write-Host { param($Object, $ForegroundColor) $script:buildMessages.Add([string]$Object) }
    function Write-Progress { param($Activity, $Status, [switch]$Completed) }
    $originalLocation = (Get-Location).Path
    Assert-Throws { Invoke-Action 'restart-backend' }
    $invocations = @(Get-Content -LiteralPath (Join-Path $fixtureBuild 'arguments.txt'))
    Assert-True ($invocations.Count -eq 1 -and $invocations[0] -eq '-B -pl offerpilot-bootstrap -am -Dmaven.test.skip=true package') 'Maven hid progress, compiled tests, retried or requested clean.'
    Assert-True ($script:buildMessages.Count -eq 7) 'Error output exceeded the five-line limit or lost errors.'
    Assert-True ((Get-Content -LiteralPath (Join-Path $fixtureBuild 'maven-build.log') -Raw).Contains('[ERROR] fixture build failure 12')) 'Native stderr was not fully saved.'
    Assert-True ((Get-Location).Path -eq $originalLocation) 'Build failure did not restore the working directory.'
    Assert-True ($script:steps.Count -eq 0 -and $script:stopped.Count -eq 0) 'Native build failure changed running services.'
}
Test-Case 'successful native build shows brief stages and returns only its fresh JAR path' {
    $fixtureBuild = Join-Path $projectRoot ('backend/target/deployment-tests/' + [Guid]::NewGuid().ToString('N'))
    $expectedJar = Join-Path $fixtureBuild 'offerpilot-bootstrap/target/offerpilot-bootstrap-0.1.0-SNAPSHOT.jar'
    New-Item -ItemType Directory -Path (Split-Path $expectedJar) -Force | Out-Null
    Set-Content -LiteralPath $expectedJar -Value 'fixture' -Encoding ASCII
    $fixtureCommand = @'
@echo off
echo [INFO] Downloading from central: fixture
echo [INFO] Downloading from central: fixture-again
echo [INFO] --- compiler:3.13.0:compile
echo [INFO] Compiling 10 source files
echo [INFO] --- surefire:3.2.5:test
echo [INFO] Tests are skipped.
echo [INFO] --- jar:3.4.2:jar
echo [INFO] Building jar: fixture.jar
echo Build fixture log
echo Build fixture stderr 1>&2
exit /b 0
'@
    Set-Content -LiteralPath (Join-Path $fixtureBuild 'mvnw.cmd') -Value $fixtureCommand -Encoding ASCII
    function New-BackendBuildDirectory { return $fixtureBuild }
    $script:buildMessages = New-Object 'Collections.Generic.List[string]'
    function Write-Host { param($Object, $ForegroundColor) $script:buildMessages.Add([string]$Object) }
    $script:buildStages = New-Object 'Collections.Generic.List[string]'
    function Write-Progress {
        param($Activity, $Status, [switch]$Completed)
        $script:buildStages.Add($(if ($Completed) { 'completed' } else { [string]$Status }))
    }
    $result = @(Build-Backend)
    Assert-True ($result.Count -eq 1 -and $result[0] -eq $expectedJar) 'Build output contaminated the JAR path.'
    Assert-True ($script:buildMessages.Count -eq 3) 'Console output was too verbose.'
    Assert-True ($script:buildStages.Count -eq 4 -and $script:buildStages[3] -eq 'completed') 'Progress was missing, repeated or not completed.'
    Assert-True (-not (($script:buildMessages -join '|').Contains('Build fixture log'))) 'Detailed Maven output leaked to the console.'
    Assert-True ((Get-Content -LiteralPath (Join-Path $fixtureBuild 'maven-build.log') -Raw).Contains('Build fixture log')) 'Detailed Maven output was lost.'
    Assert-True ((Get-Content -LiteralPath (Join-Path $fixtureBuild 'maven-build.log') -Raw).Contains('Build fixture stderr')) 'Successful build stderr was lost.'
}
Test-Case 'dry run performs no process or build work' {
    $DryRun = $true
    function New-BackendBuildDirectory { throw 'Dry run created build directory.' }
    foreach ($selected in @('start', 'start-backend', 'restart-backend', 'stop')) { Invoke-Action $selected }
    Assert-True ($script:steps.Count -eq 0 -and $script:stopped.Count -eq 0) 'Dry run changed services.'
}
Write-Host ("Passed $passed deployment lifecycle regression cases.")
