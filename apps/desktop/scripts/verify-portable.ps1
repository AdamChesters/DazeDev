$ErrorActionPreference = 'Stop'
$stage = Join-Path $env:RUNNER_TEMP 'DazeDev-portable'
$legacy = Join-Path $env:APPDATA 'com.adamchesters.dazeddev'
New-Item -ItemType Directory -Force "$legacy/models" | Out-Null
@'
{"hosts":[],"activeHost":"","activeWorkspace":"","voice":{"model":"parakeet","backend":"cpu","cleanup":true,"automatic":false,"microphone":""},"sidebar":true,"highlightMotion":"system","checkUpdates":false,"voiceSetupDismissed":true}
'@ | Set-Content "$legacy/device.json" -Encoding utf8
Set-Content "$legacy/models/migration-fixture.txt" 'preserved-model'
Copy-Item "$legacy/device.json" "$legacy-fixture.json" -Force
$modelHash = (Get-FileHash "$legacy/models/migration-fixture.txt").Hash
$env:WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS = '--remote-debugging-port=9222'
$app = Start-Process "$stage/DazeDev.exe" -PassThru -RedirectStandardError "$stage/startup-stderr.txt"
Start-Sleep -Seconds 12
if ($app.HasExited) { Get-Content "$stage/data/startup-error.txt", "$stage/startup-stderr.txt" -ErrorAction SilentlyContinue; throw "Portable app exited: $($app.ExitCode)" }
if (!(Test-Path "$stage/data/device.json")) { throw 'Settings were not migrated' }
if (!(Test-Path "$stage/data/models/migration-fixture.txt")) { throw 'Models were not migrated' }
# The app may add defaults or run its own settings upgrades on first start,
# so compare values rather than bytes. highlightMotion is upgraded on purpose.
$before = Get-Content -Raw "$legacy-fixture.json" | ConvertFrom-Json
$after = Get-Content -Raw "$stage/data/device.json" | ConvertFrom-Json
foreach ($property in $before.PSObject.Properties) {
  if ($property.Name -eq 'highlightMotion') { continue }
  $old = $property.Value
  $new = $after.($property.Name)
  if ($old -is [System.Management.Automation.PSCustomObject]) {
    foreach ($inner in $old.PSObject.Properties) {
      if ((ConvertTo-Json -Compress $inner.Value) -ne (ConvertTo-Json -Compress $new.($inner.Name))) { throw "Setting $($property.Name).$($inner.Name) changed during migration" }
    }
  } elseif ((ConvertTo-Json -Compress $old) -ne (ConvertTo-Json -Compress $new)) { throw "Setting $($property.Name) changed during migration" }
}
if ((Get-FileHash "$stage/data/models/migration-fixture.txt").Hash -ne $modelHash) { throw 'Model changed during migration' }
if (Test-Path "$legacy/device.json") { throw 'Legacy settings were copied but not moved' }
if (Test-Path "$legacy/models/migration-fixture.txt") { throw 'Legacy model was not moved' }
if (!(Test-Path "$stage/data/webview")) { throw 'WebView data is not portable' }
node scripts/verify-window.mjs
if ($LASTEXITCODE -ne 0) { throw 'Packaged interface did not load' }
Stop-Process -Id $app.Id
Remove-Item Env:WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS
Remove-Item "$legacy-fixture.json" -ErrorAction SilentlyContinue
Write-Host 'Portable startup, in-folder data and legacy migration passed.'
