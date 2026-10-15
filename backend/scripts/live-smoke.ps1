param(
    [switch]$RunLive,
    [ValidateSet('FLASH', 'PRO', 'ALL')][string]$Tier = 'FLASH',
    [ValidateRange(1, 200)][int]$MaxCalls = 40,
    [string]$EnvFile = ''
)
$ErrorActionPreference = 'Stop'
$taskProjectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$taskBuildRoot = Join-Path ([IO.Path]::GetTempPath()) ('offerpilot-live-' + [guid]::NewGuid().ToString('N'))
$taskBackendRoot = Join-Path $taskBuildRoot 'backend'
$taskLog = Join-Path $taskBuildRoot 'maven-live.log'
if (-not $EnvFile) { $EnvFile = Join-Path $taskProjectRoot 'backend/.env.local' }
$taskPreviousKey = $env:DEEPSEEK_API_KEY
$taskPreviousGate = $env:OFFERPILOT_RUN_LIVE_SMOKE
$taskPreviousProfile = $env:SPRING_PROFILES_ACTIVE
try {
    if ($RunLive) {
        if ([string]::IsNullOrWhiteSpace($env:DEEPSEEK_API_KEY) -and (Test-Path -LiteralPath $EnvFile)) {
            $taskKey = [regex]::Match([IO.File]::ReadAllText($EnvFile), '(?m)^DEEPSEEK_API_KEY\s*=\s*([^\r\n]*)$')
            if ($taskKey.Success) { $env:DEEPSEEK_API_KEY = $taskKey.Groups[1].Value.Trim().Trim('"').Trim("'") }
        }
        if ([string]::IsNullOrWhiteSpace($env:DEEPSEEK_API_KEY) -or $env:DEEPSEEK_API_KEY -match 'not.configured|replace-with|your-key') {
            throw 'A configured DEEPSEEK_API_KEY is required; its value will not be printed.'
        }
        $env:OFFERPILOT_RUN_LIVE_SMOKE = 'true'
        $env:SPRING_PROFILES_ACTIVE = 'prod'
    }
    # Snapshot current tracked and non-ignored backend sources, including uncommitted edits.
    # No target/, env files, deletion, clean, reset or modification of the original checkout.
    $taskSources = & git -C $taskProjectRoot -c core.quotepath=false ls-files --cached --others --exclude-standard -- backend
    if ($LASTEXITCODE -ne 0) { throw 'Cannot enumerate backend sources.' }
    foreach ($taskRelative in $taskSources) {
        $taskSource = [IO.Path]::GetFullPath((Join-Path $taskProjectRoot $taskRelative))
        $taskDestination = [IO.Path]::GetFullPath((Join-Path $taskBuildRoot $taskRelative))
        if (-not $taskSource.StartsWith($taskProjectRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase) -or
            -not $taskDestination.StartsWith($taskBuildRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
            throw 'Snapshot path escaped its root.'
        }
        if (-not (Test-Path -LiteralPath $taskSource -PathType Leaf)) { continue }
        New-Item -ItemType Directory -Path ([IO.Path]::GetDirectoryName($taskDestination)) -Force | Out-Null
        Copy-Item -LiteralPath $taskSource -Destination $taskDestination
    }
    $taskMaven = Get-Command mvn -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    $taskMavenPath = if ($taskMaven) { $taskMaven.Source } else { Join-Path $taskBackendRoot 'mvnw.cmd' }
    if (-not (Test-Path -LiteralPath $taskMavenPath -PathType Leaf)) { throw 'Maven or Maven Wrapper is unavailable.' }
    $taskArguments = @('-q', '-B', '-Plive', '-pl', 'offerpilot-bootstrap', '-am')
    if ($RunLive) {
        $taskArguments += @('-Dtest=DeepSeekSmokeLiveEvaluationTest', '-Dsurefire.failIfNoSpecifiedTests=false',
            "-Dofferpilot.smoke.tier=$Tier", "-Dofferpilot.smoke.max-calls=$MaxCalls", 'test')
        Write-Host "Live smoke: $Tier, at most $MaxCalls model stream requests, isolated DB/Redis/S3Mock."
    } else {
        $taskArguments += @('-DskipTests', 'test-compile')
        Write-Host 'Compiling live evaluations only; no provider calls or test containers.'
    }
    Push-Location $taskBackendRoot
    $taskPreviousErrorPreference = $ErrorActionPreference
    try {
        # Windows PowerShell 5 wraps native stderr as errors even when Maven exits 0.
        $ErrorActionPreference = 'Continue'
        & $taskMavenPath @taskArguments *> $taskLog
        if (-not $?) { $taskExit = if ($LASTEXITCODE) { $LASTEXITCODE } else { 1 } }
        else { $taskExit = $LASTEXITCODE }
    } finally {
        $ErrorActionPreference = $taskPreviousErrorPreference
        Pop-Location
    }
    if ($RunLive) {
        $taskReportPath = Join-Path $taskBackendRoot 'offerpilot-bootstrap/target/surefire-reports/TEST-com.offerpilot.DeepSeekSmokeLiveEvaluationTest.xml'
        if (Test-Path -LiteralPath $taskReportPath) {
            [xml]$taskReport = [IO.File]::ReadAllText($taskReportPath)
            $taskSuite = $taskReport.testsuite
            $taskSummary = [ordered]@{ tier=$Tier; maxCalls=$MaxCalls; tests=[int]$taskSuite.tests;
                failures=[int]$taskSuite.failures; errors=[int]$taskSuite.errors; skipped=[int]$taskSuite.skipped;
                isolatedStorage='S3Mock (authentication checked separately)';
                jobs='MCP unavailable stub; demo and offline snapshots'; realEmbedding=$false }
            [IO.File]::WriteAllText((Join-Path $taskBuildRoot 'live-smoke-summary.json'), ($taskSummary | ConvertTo-Json),
                (New-Object System.Text.UTF8Encoding($false)))
            Write-Host "Tests: $($taskSuite.tests); failures: $($taskSuite.failures); errors: $($taskSuite.errors); skipped: $($taskSuite.skipped)."
        }
    }
    if ($null -eq $taskExit -or $taskExit -ne 0) { throw "Verification failed. Inspect the private local log: $taskLog" }
    Write-Host "PASS. Evidence kept at: $taskBuildRoot"
} finally {
    $env:DEEPSEEK_API_KEY = $taskPreviousKey
    $env:OFFERPILOT_RUN_LIVE_SMOKE = $taskPreviousGate
    $env:SPRING_PROFILES_ACTIVE = $taskPreviousProfile
}
