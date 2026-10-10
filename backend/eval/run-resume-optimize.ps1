param(
    [switch]$Live,
    [string]$OutputDirectory,
    [string]$ReplayResults
)
$ErrorActionPreference = 'Stop'
$backendRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$projectRoot = [IO.Path]::GetFullPath((Join-Path $backendRoot '..'))
$taskNames = @('DEEPSEEK_API_KEY', 'DEEPSEEK_BASE_URL', 'LLM_MODEL_CHEAP',
    'OFFERPILOT_RUN_LIVE_OPTIMIZE', 'OFFERPILOT_OPTIMIZE_EVAL_OUT', 'OFFERPILOT_OPTIMIZE_EVAL_REPLAY')
$taskPrevious = @{}
foreach ($taskName in $taskNames) {
    $taskPrevious[$taskName] = [Environment]::GetEnvironmentVariable($taskName, 'Process')
}
$taskExit = 0
Push-Location $backendRoot
try {
    # Only model configuration is loaded; never load database credentials or print values.
    $taskEnvPath = Join-Path $backendRoot '.env.local'
    if ($Live -and (Test-Path -LiteralPath $taskEnvPath)) {
        foreach ($taskLine in Get-Content -LiteralPath $taskEnvPath) {
            if ($taskLine -match '^\s*([A-Z][A-Z0-9_]*)\s*=(.*)$') {
                $taskName = $matches[1]
                $taskValue = $matches[2].Trim()
                if ($taskName -notin @('DEEPSEEK_API_KEY', 'DEEPSEEK_BASE_URL', 'LLM_MODEL_CHEAP')) { continue }
                if (-not [string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($taskName, 'Process'))) { continue }
                if ($taskValue.Length -ge 2 -and (($taskValue.StartsWith('"') -and $taskValue.EndsWith('"')) -or
                        ($taskValue.StartsWith("'") -and $taskValue.EndsWith("'")))) {
                    $taskValue = $taskValue.Substring(1, $taskValue.Length - 2)
                }
                [Environment]::SetEnvironmentVariable($taskName, $taskValue, 'Process')
            }
        }
    }
    # Inherited opt-in must not make the default offline command call a model.
    $env:OFFERPILOT_RUN_LIVE_OPTIMIZE = 'false'
    $env:OFFERPILOT_OPTIMIZE_EVAL_REPLAY = ''
    if (-not [string]::IsNullOrWhiteSpace($ReplayResults)) {
        $env:OFFERPILOT_OPTIMIZE_EVAL_REPLAY = [IO.Path]::GetFullPath($ReplayResults)
    }
    $taskTests = 'ResumeOptimizerTest,ResumeOptimizationBenchmarkTest,ResumeOptimizationLiveEvaluationTest,ResumeOptimizationEvidenceReplayTest'
    if ($Live) {
        if ([string]::IsNullOrWhiteSpace($env:DEEPSEEK_API_KEY)) { throw 'No configured DeepSeek credential; nothing was sent.' }
        $env:OFFERPILOT_RUN_LIVE_OPTIMIZE = 'true'
        if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
            $OutputDirectory = Join-Path $projectRoot ('docs/eval-results/resume-optimize/' +
                (Get-Date -Format 'yyyy-MM-dd-HHmmss') + '-' + [Guid]::NewGuid().ToString('N'))
        }
        $env:OFFERPILOT_OPTIMIZE_EVAL_OUT = [IO.Path]::GetFullPath($OutputDirectory)
        if (Test-Path -LiteralPath (Join-Path $env:OFFERPILOT_OPTIMIZE_EVAL_OUT 'results.json')) {
            throw 'Output already contains a run; choose a new directory to preserve evidence.'
        }
        Write-Output ('Evaluation output: ' + $env:OFFERPILOT_OPTIMIZE_EVAL_OUT)
    }
    $taskRevision = (git rev-parse HEAD).Trim() + '+evaluation-working-tree'
    $taskProfiles = @()
    if ($Live) { $taskProfiles = @('-Plive') }
    & './mvnw.cmd' -q -B @taskProfiles -pl offerpilot-bootstrap -am "-Dtest=$taskTests" '-Dsurefire.failIfNoSpecifiedTests=false' "-Dofferpilot.eval.revision=$taskRevision" test
    $taskExit = $LASTEXITCODE
    if ($Live -and (Test-Path -LiteralPath (Join-Path $env:OFFERPILOT_OPTIMIZE_EVAL_OUT 'human-review.json'))) {
        & python -B (Join-Path $PSScriptRoot 'summarize-resume-optimize.py') $env:OFFERPILOT_OPTIMIZE_EVAL_OUT
        if ($LASTEXITCODE -ne 0 -and $taskExit -eq 0) { $taskExit = $LASTEXITCODE }
    }
} finally {
    Pop-Location
    foreach ($taskName in $taskNames) {
        [Environment]::SetEnvironmentVariable($taskName, $taskPrevious[$taskName], 'Process')
    }
}
exit $taskExit
