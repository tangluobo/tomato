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

function Resolve-VcRuntimeDirectory {
    $requiredFiles = @(
        'msvcp140.dll',
        'vcruntime140.dll',
        'vcruntime140_1.dll'
    )
    $candidateDirectories = @()

    if ($env:VCToolsRedistDir -and (Test-Path -LiteralPath $env:VCToolsRedistDir)) {
        $candidateDirectories += @(Get-ChildItem -Path (
            Join-Path $env:VCToolsRedistDir 'x64\Microsoft.VC*.CRT'
        ) -Directory -ErrorAction SilentlyContinue)
    }

    $programFilesX86 = [Environment]::GetFolderPath('ProgramFilesX86')
    $vswhere = Join-Path $programFilesX86 'Microsoft Visual Studio\Installer\vswhere.exe'
    if (Test-Path -LiteralPath $vswhere) {
        $installations = @(& $vswhere -products * -property installationPath)
        foreach ($installation in $installations) {
            if ([string]::IsNullOrWhiteSpace($installation)) {
                continue
            }
            $redistRoot = Join-Path $installation 'VC\Redist\MSVC'
            if (-not (Test-Path -LiteralPath $redistRoot)) {
                continue
            }
            foreach ($versionDirectory in (Get-ChildItem -LiteralPath $redistRoot -Directory |
                    Sort-Object Name -Descending)) {
                $candidateDirectories += @(Get-ChildItem -Path (
                    Join-Path $versionDirectory.FullName 'x64\Microsoft.VC*.CRT'
                ) -Directory -ErrorAction SilentlyContinue)
            }
        }
    }

    foreach ($directory in ($candidateDirectories | Select-Object -Unique)) {
        $missingFiles = @($requiredFiles | Where-Object {
            -not (Test-Path -LiteralPath (Join-Path $directory.FullName $_))
        })
        if ($missingFiles.Count -eq 0) {
            return $directory.FullName
        }
    }

    throw 'Microsoft Visual C++ x64 app-local runtime was not found. Install the MSVC Build Tools with the VC++ Redistributable component.'
}

function Add-VcRuntimeToBundle {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Destination
    )

    $runtimeDirectory = Resolve-VcRuntimeDirectory
    foreach ($runtimeName in @('msvcp140.dll', 'vcruntime140.dll', 'vcruntime140_1.dll')) {
        $runtimeFile = Join-Path $runtimeDirectory $runtimeName
        Copy-Item -LiteralPath $runtimeFile -Destination $Destination -Force
        Write-Host "Bundled Visual C++ runtime: $runtimeName"
    }
}

function Update-NativeArchives {
    $zipOutput = Join-Path $distDirectory "$bundleName.zip"
    $tarOutput = Join-Path $distDirectory "$bundleName.tar.gz"
    Remove-Item -LiteralPath $zipOutput, $tarOutput -Force -ErrorAction SilentlyContinue

    Compress-Archive -Path $bundleDirectory -DestinationPath $zipOutput -CompressionLevel Optimal
    $tar = Resolve-PackagingTool -Name 'tar.exe'
    & $tar '-czf' $tarOutput '-C' (Split-Path $bundleDirectory -Parent) $bundleName
    if ($LASTEXITCODE -ne 0) {
        throw "tar.exe failed with exit code $LASTEXITCODE"
    }
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

# Native Image uses the installed MSVC toolchain, but its app-local runtime DLLs
# are not emitted into target automatically. Keep ZIP/tar.gz/MSI/NSIS aligned by
# adding the complete x64 CRT redist directory to the shared bundle first.
Add-VcRuntimeToBundle -Destination $bundleDirectory
Update-NativeArchives

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
