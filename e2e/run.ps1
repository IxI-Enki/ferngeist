# Installs the app and the e2e APK, then runs the instrumentation tests. harness\gateway.ps1 must
# already be running; the tests mint their own pairing payloads through an `adb reverse` of its admin port.
#   .\e2e\run.ps1 -Serial emulator-5554 [-Test com.tamimarafat.ferngeist.e2e.MultiAgentBackgroundTest#name]
param(
    [Parameter(Mandatory)][string]$Serial,
    [string]$Test
)
$ErrorActionPreference = "Stop"
$root = Resolve-Path "$PSScriptRoot\.."
$adb = "adb", "-s", $Serial

Push-Location $root
try { .\gradlew.bat :app:assembleGoogleDebug; if ($LASTEXITCODE) { throw "app build failed" } } finally { Pop-Location }
Push-Location $root
try { .\gradlew.bat :e2e:assembleGoogleDebug; if ($LASTEXITCODE) { throw "e2e build failed" } } finally { Pop-Location }

& $adb[0] $adb[1..2] install -r -g (Get-ChildItem "$root\app\build\outputs\apk\google\debug\*.apk" | Select-Object -First 1).FullName
& $adb[0] $adb[1..2] install -r -g "$root\e2e\build\outputs\apk\google\debug\e2e-google-debug.apk"

# Chats from earlier runs would otherwise show up on the session list.
Remove-Item "$PSScriptRoot\harness\.work\agent-state\*.json" -ErrorAction SilentlyContinue

& $adb[0] $adb[1..2] reverse tcp:5799 tcp:5799

# One instrumentation run per test: a test that follows another in the same process meets its leftovers.
$class = "com.tamimarafat.ferngeist.e2e.MultiAgentBackgroundTest"
$tests = if ($Test) { @($Test) } else {
    Select-String -Path "$PSScriptRoot\src\main\kotlin\com\tamimarafat\ferngeist\e2e\*Test.kt" -Pattern '@Test' -Context 0, 1 |
        ForEach-Object { if ($_.Context.PostContext[0] -match 'fun (\w+)\(') { "$class#$($Matches[1])" } }
}
foreach ($t in $tests) {
    Write-Host "== $t"
    & $adb[0] $adb[1..2] shell am instrument -w -e class $t com.tamimarafat.ferngeist.e2e/androidx.test.runner.AndroidJUnitRunner
}
