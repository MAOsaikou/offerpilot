function Resolve-OfferPilotRuntimeRoot([string]$RuntimeRoot) {
    if ([string]::IsNullOrWhiteSpace($RuntimeRoot)) {
        $RuntimeRoot = $env:OFFERPILOT_RUNTIME_ROOT
    }
    if ([string]::IsNullOrWhiteSpace($RuntimeRoot)) {
        $RuntimeRoot = 'D:\OfferPilot'
    }
    return [IO.Path]::GetFullPath($RuntimeRoot)
}
