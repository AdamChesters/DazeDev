param([switch]$Offline)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'windows-release-env.ps1')
$cargoArgs = @('build', '--release', '--bins', '--manifest-path', (Join-Path $root 'src-tauri/Cargo.toml'), '--features', 'vulkan,custom-protocol', '--locked')
if ($Offline) { $cargoArgs += '--offline' }
& cargo @cargoArgs
if ($LASTEXITCODE -ne 0) { throw "Windows release build failed: $LASTEXITCODE" }
