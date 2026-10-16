param([switch]$Offline)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'windows-release-env.ps1')
$manifest = Join-Path $root 'src-tauri/Cargo.toml'
$cargoArgs = @('build', '--release', '--bin', 'DazeDev', '--manifest-path', $manifest, '--features', 'vulkan,custom-protocol', '--locked')
if ($Offline) { $cargoArgs += '--offline' }
& cargo @cargoArgs
if ($LASTEXITCODE -ne 0) { throw "Windows release build failed: $LASTEXITCODE" }
# The session broker needs neither the window nor the voice code.
$sessionArgs = @('build', '--release', '--bin', 'DazeDevSession', '--manifest-path', $manifest, '--no-default-features', '--locked')
if ($Offline) { $sessionArgs += '--offline' }
& cargo @sessionArgs
if ($LASTEXITCODE -ne 0) { throw "Windows session broker build failed: $LASTEXITCODE" }
