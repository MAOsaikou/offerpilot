param([string]$NginxDirectory = '', [switch]$SkipBuild)
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$runtimeRoot = 'D:\OfferPilot'
if (-not $NginxDirectory) { $NginxDirectory = Join-Path $runtimeRoot 'nginx-1.31.5' }
$NginxDirectory = [IO.Path]::GetFullPath($NginxDirectory)
if (-not $NginxDirectory.StartsWith($runtimeRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'NginxDirectory must be inside D:\OfferPilot.'
}
if (-not $SkipBuild) {
    Push-Location (Join-Path $projectRoot 'frontend')
    $previousApi = $env:VITE_API_BASE_URL
    $previousMock = $env:VITE_ENABLE_MOCK
    try {
        $env:VITE_API_BASE_URL = '/api/v1'
        $env:VITE_ENABLE_MOCK = 'false'
        $localPnpm = Join-Path $projectRoot '.corepack/v1/pnpm/12.3.4/bin/pnpm.mjs'
        if (Test-Path -LiteralPath $localPnpm) { & node $localPnpm build }
        elseif (Get-Command pnpm -ErrorAction SilentlyContinue) { & pnpm build }
        # Fall back to corepack when no global pnpm is installed.
        elseif (Get-Command corepack -ErrorAction SilentlyContinue) { & corepack pnpm build }
        else { throw 'pnpm not found. Install Node.js 22+ then run: npm i -g pnpm (or corepack enable pnpm).' }
        if ($LASTEXITCODE -ne 0) { throw 'Frontend build failed.' }
    } finally {
        $env:VITE_API_BASE_URL = $previousApi
        $env:VITE_ENABLE_MOCK = $previousMock
        Pop-Location
    }
}
$dist = Join-Path $projectRoot 'frontend/dist'
if (-not (Test-Path -LiteralPath (Join-Path $dist 'index.html'))) { throw 'Missing frontend/dist/index.html. Build first.' }
New-Item -ItemType Directory -Path $runtimeRoot -Force | Out-Null
if (-not (Test-Path -LiteralPath (Join-Path $NginxDirectory 'nginx.exe'))) {
    $zip = Join-Path $runtimeRoot 'nginx-1.31.5.zip'
    Write-Host 'Downloading official Nginx for Windows...'
    Invoke-WebRequest -UseBasicParsing -Uri 'https://nginx.org/download/nginx-1.31.5.zip' -OutFile $zip
    Expand-Archive -LiteralPath $zip -DestinationPath $runtimeRoot -Force
    if (-not (Test-Path -LiteralPath (Join-Path $NginxDirectory 'nginx.exe'))) { throw 'Expected nginx.exe missing. Use the default directory or supply an existing runtime.' }
}
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'nginx.conf') -Destination (Join-Path $NginxDirectory 'conf/nginx.conf') -Force
# Copy build entries without deleting any existing file or directory.
Get-ChildItem -LiteralPath $dist | ForEach-Object {
    Copy-Item -LiteralPath $_.FullName -Destination (Join-Path $NginxDirectory 'html') -Recurse -Force
}
Push-Location $NginxDirectory
try { & './nginx.exe' -t; if ($LASTEXITCODE -ne 0) { throw 'Nginx configuration check failed.' } }
finally { Pop-Location }
Write-Host ('Ready: ' + (Join-Path $NginxDirectory 'nginx.exe'))
Write-Host 'Open http://localhost:8080 after starting nginx.exe. Backend target: 127.0.0.1:8081.'
