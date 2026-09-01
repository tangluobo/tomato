param(
    [Parameter(Mandatory = $true)]
    [string]$Version,

    [Parameter(Mandatory = $true)]
    [ValidateSet('x86_64')]
    [string]$Architecture
)

$ErrorActionPreference = 'Stop'

$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$bundleName = "tomato-$Version-windows-$Architecture"
$bundleDirectory = Join-Path $projectRoot "target\native-package\$bundleName"
$distDirectory = Join-Path $projectRoot 'dist'
$workDirectory = Join-Path $projectRoot 'target\native-windows'
$iconFile = Join-Path $projectRoot 'packaging\tomato.ico'

if (-not (Test-Path -LiteralPath (Join-Path $bundleDirectory 'tomato.exe'))) {
    throw "Missing AOT executable: $bundleDirectory\tomato.exe"
}

function Resolve-PackagingTool {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Name,
        [string[]]$FallbackPatterns = @()
    )

    $command = Get-Command $Name -ErrorAction SilentlyContinue
    if ($null -ne $command) {
        return $command.Source
    }

    foreach ($pattern in $FallbackPatterns) {
        $candidate = Get-ChildItem -Path $pattern -File -ErrorAction SilentlyContinue |
            Select-Object -First 1
        if ($null -ne $candidate) {
            return $candidate.FullName
        }
    }

    throw "$Name was not found. Install WiX Toolset 3.14 and NSIS before packaging."
}

$heat = Resolve-PackagingTool -Name 'heat.exe' -FallbackPatterns @(
    'C:\Program Files (x86)\WiX Toolset v3.14\bin\heat.exe',
    'C:\Program Files (x86)\WiX Toolset v3.*\bin\heat.exe'
)
$candle = Resolve-PackagingTool -Name 'candle.exe' -FallbackPatterns @(
    'C:\Program Files (x86)\WiX Toolset v3.14\bin\candle.exe',
    'C:\Program Files (x86)\WiX Toolset v3.*\bin\candle.exe'
)
$light = Resolve-PackagingTool -Name 'light.exe' -FallbackPatterns @(
    'C:\Program Files (x86)\WiX Toolset v3.14\bin\light.exe',
    'C:\Program Files (x86)\WiX Toolset v3.*\bin\light.exe'
)
$makensis = Resolve-PackagingTool -Name 'makensis.exe' -FallbackPatterns @(
    'C:\Program Files (x86)\NSIS\makensis.exe',
    'C:\Program Files\NSIS\makensis.exe'
)

if (Test-Path -LiteralPath $workDirectory) {
    Remove-Item -LiteralPath $workDirectory -Recurse -Force
}
New-Item -ItemType Directory -Path $workDirectory -Force | Out-Null
New-Item -ItemType Directory -Path $distDirectory -Force | Out-Null

$harvestFile = Join-Path $workDirectory 'native-files.wxs'
$heatArguments = @(
    'dir', $bundleDirectory, '-nologo', '-cg', 'NativeFiles', '-dr', 'INSTALLFOLDER',
    '-srd', '-sreg', '-scom', '-gg', '-var', 'var.SourceDir', '-out', $harvestFile
)
& $heat @heatArguments
if ($LASTEXITCODE -ne 0) {
    throw "heat.exe failed with exit code $LASTEXITCODE"
}

$wixSource = Join-Path $projectRoot 'packaging\windows\tomato.wxs'
$wixOutputDirectory = "$workDirectory\"
$candleArguments = @(
    '-nologo', '-arch', 'x64', "-dSourceDir=$bundleDirectory", "-dVersion=$Version",
    "-dIconFile=$iconFile", '-out', $wixOutputDirectory, $wixSource, $harvestFile
)
& $candle @candleArguments
if ($LASTEXITCODE -ne 0) {
    throw "candle.exe failed with exit code $LASTEXITCODE"
}

$msiOutput = Join-Path $distDirectory "$bundleName.msi"
$lightArguments = @(
    '-nologo', '-sval', '-spdb', '-out', $msiOutput,
    (Join-Path $workDirectory 'tomato.wixobj'),
    (Join-Path $workDirectory 'native-files.wixobj')
)
& $light @lightArguments
if ($LASTEXITCODE -ne 0) {
    throw "light.exe failed with exit code $LASTEXITCODE"
}

$exeOutput = Join-Path $distDirectory "$bundleName.exe"
$nsisSource = Join-Path $projectRoot 'packaging\windows\tomato.nsi'
$nsisArguments = @(
    '/V2', "/DVERSION=$Version", "/DSOURCE_DIR=$bundleDirectory",
    "/DOUTPUT_FILE=$exeOutput", "/DICON_FILE=$iconFile", $nsisSource
)
& $makensis @nsisArguments
if ($LASTEXITCODE -ne 0) {
    throw "makensis.exe failed with exit code $LASTEXITCODE"
}

Write-Host "Windows AOT packages created in $distDirectory"
