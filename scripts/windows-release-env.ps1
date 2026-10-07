$ErrorActionPreference = 'Stop'

# Keep workstation paths out of release binaries and native library diagnostics.
$root = Split-Path $PSScriptRoot -Parent
$cargoHome = if ($env:CARGO_HOME) { $env:CARGO_HOME } else { Join-Path $env:USERPROFILE '.cargo' }
$target = if ($env:CARGO_TARGET_DIR) { $env:CARGO_TARGET_DIR } else { Join-Path $root 'src-tauri/target' }
$mappings = @(
  @($env:USERPROFILE, 'C:\build\user'),
  @($env:TEMP, 'C:\build\temp'),
  @($cargoHome, 'C:\build\cargo'),
  @($root, 'C:\build\source'),
  @($target, 'C:\build\target')
)
$rustFlags = @()
if ($env:CARGO_ENCODED_RUSTFLAGS) {
  $rustFlags += $env:CARGO_ENCODED_RUSTFLAGS.Split([char]31)
} elseif ($env:RUSTFLAGS) {
  $rustFlags += $env:RUSTFLAGS -split '\s+' | Where-Object { $_ }
}
$nativeFlags = @('/experimental:deterministic')
foreach ($mapping in $mappings) {
  if (!$mapping[0]) { continue }
  $source = [IO.Path]::GetFullPath($mapping[0]).TrimEnd('\', '/')
  foreach ($prefix in @($source, $source.Replace('\', '/')) | Select-Object -Unique) {
    $rustFlags += "--remap-path-prefix=$prefix=$($mapping[1])"
    $nativeFlags += "/pathmap:`"$prefix`"=`"$($mapping[1])`""
  }
}
$rustFlags += '-Clink-arg=/PDBALTPATH:%_PDB%'
$env:CARGO_ENCODED_RUSTFLAGS = $rustFlags -join [char]31
$env:CFLAGS = (@($env:CFLAGS) + $nativeFlags) -join ' '
$env:CXXFLAGS = (@($env:CXXFLAGS) + $nativeFlags) -join ' '
