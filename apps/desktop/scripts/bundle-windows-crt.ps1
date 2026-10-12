$ErrorActionPreference = 'Stop'
$vswhere = Join-Path ${env:ProgramFiles(x86)} 'Microsoft Visual Studio/Installer/vswhere.exe'
$installation = & $vswhere -latest -products '*' -property installationPath
if (!$installation) { throw 'Visual Studio installation not found' }
$version = Get-ChildItem "$installation/VC/Redist/MSVC" -Directory | Where-Object { $_.Name -match '^\d+\.\d+\.\d+' } | Sort-Object { [version]$_.Name } -Descending | Select-Object -First 1
$redist = if ($version) { $version.FullName } else { Join-Path $installation 'VC/Redist/MSVC/v143' }
$crt = Join-Path $redist 'x64/Microsoft.VC143.CRT'
if (!(Test-Path "$crt/msvcp140.dll")) { throw 'Redistributable x64 C++ runtime not found' }
foreach ($destination in @('src-tauri/transcribe-libs', 'src-tauri/runtime')) {
  New-Item -ItemType Directory -Force -Path $destination | Out-Null
  Get-ChildItem $crt -Filter '*.dll' | Copy-Item -Destination $destination -Force
}
Write-Host 'Bundled the app-local Microsoft C++ runtime for both native processes.'
