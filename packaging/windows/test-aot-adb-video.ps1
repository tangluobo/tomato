param(
    [string]$Executable = 'target\tomato.exe',
    [int]$TimeoutSeconds = 60
)
$ErrorActionPreference = 'Stop'
$binary = (Resolve-Path -LiteralPath $Executable).Path
$work = Join-Path (Split-Path $binary -Parent) ('adb-video-check-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $work -Force | Out-Null
$stdout = Join-Path $work 'stdout.log'
$stderr = Join-Path $work 'stderr.log'
$cache = Join-Path $work 'native-cache'
$process = Start-Process -FilePath $binary -WorkingDirectory (Split-Path $binary -Parent) `
    -ArgumentList @(('"-Dorg.bytedeco.javacpp.cachedir=' + $cache + '"'), '--aot-adb-video-self-check') `
    -WindowStyle Hidden -RedirectStandardOutput $stdout -RedirectStandardError $stderr -PassThru
# Keep a handle so PowerShell can reliably read ExitCode after the GUI subsystem exe exits.
$processHandle = $process.Handle
if (-not $process.WaitForExit($TimeoutSeconds * 1000)) {
    $process.Kill()
    throw "ADB native decoder check timed out; logs: $work"
}
$process.WaitForExit()
$output = Get-Content -LiteralPath $stdout -Raw
if ($process.ExitCode -ne 0 -or $output -notmatch 'AOT_ADB_VIDEO_SELF_CHECK_PASS') {
    Get-Content -LiteralPath $stderr
    throw "ADB native decoder check failed; logs: $work"
}
Write-Output $output
