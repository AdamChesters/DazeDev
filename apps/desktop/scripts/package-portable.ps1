$ErrorActionPreference = 'Stop'
$version = (Get-Content package.json | ConvertFrom-Json).version
$stage = Join-Path $env:RUNNER_TEMP 'DazeDev-portable'
$zip = Join-Path $PWD "artifacts/DazeDev_${version}_windows-x64.zip"
if ((Test-Path $stage) -or (Test-Path $zip)) {
  throw 'Use a fresh staging and output directory. Existing portable files must not enter a new package.'
}
New-Item -ItemType Directory $stage | Out-Null
Copy-Item src-tauri/target/release/DazeDev.exe $stage
Get-ChildItem src-tauri/transcribe-libs -Filter '*.dll' | Copy-Item -Destination $stage
Copy-Item src-tauri/runtime "$stage/runtime" -Recurse
Copy-Item src-tauri/target/release/DazeDevSession.exe "$stage/runtime/DazeDevSession.exe"
Copy-Item src-tauri/licenses "$stage/licenses" -Recurse
@'
DazeDev portable
Extract this ZIP into a writable folder and run DazeDev.exe.
Settings, models and WebView data live in data/ beside the executable.
First launch moves old DazedDev installer settings/models here. Conflicts are kept in data/migration-recovery/.
Windows x64 with Microsoft Edge WebView2 Runtime is required (normally present on Windows 11).
Updates check published GitHub releases over HTTPS. Download/restart requires consent and preserves data/.
'@ | Set-Content "$stage/README.txt"
New-Item -ItemType Directory -Force artifacts | Out-Null
# Windows PowerShell 5 Compress-Archive writes backslash entry names, which the
# portable updater correctly rejects. ZIP paths use forward slashes everywhere.
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [System.IO.Compression.ZipFile]::Open($zip, [System.IO.Compression.ZipArchiveMode]::Create)
try {
  foreach ($file in Get-ChildItem -LiteralPath $stage -File -Recurse) {
    $entry = $file.FullName.Substring($stage.Length + 1).Replace('\', '/')
    [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
      $archive, $file.FullName, $entry, [System.IO.Compression.CompressionLevel]::Optimal
    ) | Out-Null
  }
} finally {
  $archive.Dispose()
}
Write-Host "Portable archive: $zip"
