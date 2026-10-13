param(
    [string]$Executable = 'target\tomato.exe',
    [int]$TimeoutSeconds = 120
)
$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$binary = (Resolve-Path -LiteralPath $Executable).Path
$work = Join-Path $projectRoot ('target\aot-ui-check-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $work -Force | Out-Null
$profile = Join-Path $work 'profile'
$stdout = Join-Path $work 'stdout.log'
$stderr = Join-Path $work 'stderr.log'
$previousDiagnostics = $env:TOMATO_CONSOLE_DIAGNOSTICS
try {
    $env:TOMATO_CONSOLE_DIAGNOSTICS = '1'
    $process = Start-Process -FilePath $binary -WorkingDirectory (Split-Path $binary -Parent) `
        -ArgumentList @('--aot-ui-self-check', ('"' + $profile + '"')) `
        -WindowStyle Normal -RedirectStandardOutput $stdout -RedirectStandardError $stderr -PassThru
    $processHandle = $process.Handle
    if (-not $process.WaitForExit($TimeoutSeconds * 1000)) {
        $process.Kill()
        throw "AOT UI check timed out; logs: $work"
    }
    $process.WaitForExit()
    $output = Get-Content -LiteralPath $stdout -Raw
    if ($process.ExitCode -ne 0 -or $output -notmatch 'AOT_UI_SELF_CHECK_PASS' `
            -or $output -notmatch 'AOT_CLIENT_SELF_CHECK_PASS') {
        Get-Content -LiteralPath $stderr
        throw "AOT UI check failed; logs: $work"
    }
    Write-Host $output
} finally {
    $env:TOMATO_CONSOLE_DIAGNOSTICS = $previousDiagnostics
}
