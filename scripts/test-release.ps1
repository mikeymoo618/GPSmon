param([ValidateSet(28,36)][int]$Api = 36, [string]$RunName = ('api' + $Api + '-' + (Get-Date -Format 'yyyyMMdd-HHmmss')))
# GPSmon release verification runner v1.0. Uses dedicated GPSmon AVDs only.
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
Set-Location -LiteralPath $projectRoot
if ($RunName -notmatch '^[a-zA-Z0-9.-]+$') { throw 'Invalid run name' }
$sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
$adb = Join-Path $sdk 'platform-tools\adb.exe'
$java = 'C:\Program Files\Android\Android Studio\jbr\bin\java.exe'
$reference = Join-Path (Split-Path -Parent $projectRoot) 'AZScreen\ScreenOhRecord\.testing'
$emulator = Join-Path $reference 'android16\emulator\emulator.exe'
$avdName = 'GPSmon' + $Api
$port = if ($Api -eq 28) { 5572 } else { 5570 }
$serial = 'emulator-' + $port
$avdHome = Join-Path $projectRoot '.testing\avd'
$avdDirectory = Join-Path $avdHome ($avdName + '.avd')
New-Item -ItemType Directory -Force -Path $avdDirectory | Out-Null
$template = if ($Api -eq 28) { 'avd28x64\ScreenOhRecord9.avd\config.ini' } else { 'avd36\ScreenOhRecord16.avd\config.ini' }
$config = (Get-Content -LiteralPath (Join-Path $reference $template) -Raw).Replace('ScreenOhRecord16',$avdName).Replace('ScreenOhRecord9',$avdName)
$config | Set-Content -LiteralPath (Join-Path $avdDirectory 'config.ini')
@('avd.ini.encoding=UTF-8', ('path=' + $avdDirectory), ('target=android-' + $Api)) | Set-Content -LiteralPath (Join-Path $avdHome ($avdName + '.ini'))
$previousAvdHome = $env:ANDROID_AVD_HOME
$previousSdkRoot = $env:ANDROID_SDK_ROOT
$previousPassword = $env:GPSMON_KEYSTORE_PASSWORD
$previousAdbPort = $env:ANDROID_ADB_SERVER_PORT
$env:ANDROID_ADB_SERVER_PORT = '15037'
$emulatorProcess = $null
$logProcess = $null
try {
    if ((& $adb devices) -match ([regex]::Escape($serial) + '\s')) { throw 'Dedicated test port is already occupied' }
    $env:ANDROID_AVD_HOME = $avdHome
    $env:ANDROID_SDK_ROOT = $sdk
    $arguments = @('-avd',$avdName,'-port',"$port",'-no-window','-no-audio','-no-snapshot','-no-boot-anim','-gpu','swiftshader','-netfast')
    $emulatorProcess = Start-Process -FilePath $emulator -ArgumentList $arguments -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput ('artifacts/' + $RunName + '-emulator.log') -RedirectStandardError ('artifacts/' + $RunName + '-emulator-error.log')
    $bootDeadline = [DateTime]::UtcNow.AddMinutes(5)
    do {
        Start-Sleep -Seconds 2
        if ($emulatorProcess.HasExited) { throw 'Emulator exited before boot' }
        $boot = & $adb -s $serial shell getprop sys.boot_completed 2>$null
        if ([DateTime]::UtcNow -gt $bootDeadline) { throw 'Emulator boot deadline exceeded' }
    } until ($boot -contains '1')
    $identity = & $adb -s $serial emu avd name
    if ($identity -notcontains $avdName) { throw 'Task-owned emulator identity mismatch' }
    $logProcess = Start-Process -FilePath $adb -ArgumentList @('-s',$serial,'logcat','-v','threadtime') -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput ('artifacts/' + $RunName + '-logcat.log') -RedirectStandardError ('artifacts/' + $RunName + '-logcat-error.log')
    $env:GPSMON_KEYSTORE_PASSWORD = (Get-Content .local-signing/signing.properties | Where-Object { $_.StartsWith('storePassword=') }).Substring(14)
    $testApk = 'artifacts/GPSmon-test-1.1.1-api' + $Api + '.apk'
    & $java -jar (Join-Path $sdk 'build-tools\36.0.0\lib\apksigner.jar') sign --ks .local-signing/gpsmon.p12 --ks-key-alias gpsmon --ks-pass env:GPSMON_KEYSTORE_PASSWORD --out $testApk app/build/outputs/apk/androidTest/release/app-release-androidTest.apk
    if ($LASTEXITCODE -ne 0) { throw 'Test harness signing failed' }
    # Only synthetic GPSmon test data in this dedicated AVD is replaced for the older-version fixture.
    & $adb -s $serial uninstall com.gpsmon.app > ('artifacts/' + $RunName + '-fixture-reset.log') 2>&1
    & $adb -s $serial install -r artifacts/GPSmon-1.1.0.apk
    if ($LASTEXITCODE -ne 0) { throw 'Reitti upgrade fixture installation failed' }
    & $adb -s $serial install -r $testApk
    if ($LASTEXITCODE -ne 0) { throw 'Reitti upgrade harness installation failed' }
    & $adb -s $serial shell am instrument -w -e mode reittiSeed com.gpsmon.app.test/com.gpsmon.app.TripTestRunner > ('artifacts/' + $RunName + '-reitti-upgrade-seed.log')
    if ((Get-Content ('artifacts/' + $RunName + '-reitti-upgrade-seed.log') -Raw) -notmatch 'PASS: signed upgrade reittiSeed') { throw 'Reitti upgrade seeding failed' }
    & $adb -s $serial install -r artifacts/GPSmon-1.1.1.apk
    if ($LASTEXITCODE -ne 0) { throw 'Reitti upgrade installation failed' }
    & $adb -s $serial shell am instrument -w -e mode reittiVerify com.gpsmon.app.test/com.gpsmon.app.TripTestRunner > ('artifacts/' + $RunName + '-reitti-upgrade-verify.log')
    if ((Get-Content ('artifacts/' + $RunName + '-reitti-upgrade-verify.log') -Raw) -notmatch 'PASS: signed upgrade reittiVerify') { throw 'Reitti settings/checkpoint preservation failed' }
    & $adb -s $serial uninstall com.gpsmon.app > ('artifacts/' + $RunName + '-legacy-fixture-reset.log') 2>&1
    & $adb -s $serial install -r artifacts/GPSmon-1.0.0.apk
    if ($LASTEXITCODE -ne 0) { throw 'Release installation failed' }
    & $adb -s $serial install -r $testApk
    if ($LASTEXITCODE -ne 0) { throw 'Harness installation failed' }
    & $adb -s $serial shell am instrument -w -e mode seed com.gpsmon.app.test/com.gpsmon.app.TripTestRunner > ('artifacts/' + $RunName + '-upgrade-seed.log')
    if ((Get-Content ('artifacts/' + $RunName + '-upgrade-seed.log') -Raw) -notmatch 'PASS: signed upgrade seed') { throw 'Upgrade fixture seeding failed' }
    & $adb -s $serial install -r artifacts/GPSmon-1.1.1.apk
    if ($LASTEXITCODE -ne 0) { throw 'Signed release upgrade failed' }
    & $adb -s $serial shell am instrument -w -e mode verify com.gpsmon.app.test/com.gpsmon.app.TripTestRunner > ('artifacts/' + $RunName + '-upgrade-verify.log')
    if ((Get-Content ('artifacts/' + $RunName + '-upgrade-verify.log') -Raw) -notmatch 'PASS: signed upgrade verify') { throw 'Upgrade preservation failed' }
    $permissions = @('android.permission.ACCESS_COARSE_LOCATION','android.permission.ACCESS_FINE_LOCATION')
    if ($Api -ge 29) { $permissions += 'android.permission.ACCESS_BACKGROUND_LOCATION' }
    if ($Api -ge 31) { $permissions += 'android.permission.BLUETOOTH_CONNECT' }
    if ($Api -ge 33) { $permissions += 'android.permission.POST_NOTIFICATIONS' }
    foreach ($permission in $permissions) {
        & $adb -s $serial shell pm grant com.gpsmon.app $permission
        if ($LASTEXITCODE -ne 0) { throw ('Permission grant failed ' + $permission) }
    }
    & $adb -s $serial shell appops set com.gpsmon.app android:mock_location allow
    & $adb -s $serial shell settings put secure location_mode 3
    $runnerLog = Join-Path $projectRoot ('artifacts/' + $RunName + '-instrumentation.log')
    $runner = Start-Process -FilePath $adb -ArgumentList @('-s',$serial,'shell','am','instrument','-w','com.gpsmon.app.test/com.gpsmon.app.TripTestRunner') -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput $runnerLog -RedirectStandardError ('artifacts/' + $RunName + '-instrumentation-error.log')
    $testDeadline = [DateTime]::UtcNow.AddMinutes(7)
    while (-not $runner.HasExited) {
        Start-Sleep -Seconds 2
        if ([DateTime]::UtcNow -gt $testDeadline) { Stop-Process -Id $runner.Id; throw 'Test deadline exceeded' }
    }
    Get-Content -LiteralPath $runnerLog
    $results = Get-Content -LiteralPath $runnerLog -Raw
    & $adb -s $serial shell dumpsys activity services com.gpsmon.app > ('artifacts/' + $RunName + '-services.txt')
    & $adb -s $serial shell dumpsys location > ('artifacts/' + $RunName + '-location.txt')
    & $adb -s $serial shell dumpsys package com.gpsmon.app > ('artifacts/' + $RunName + '-package.txt')
    & $adb -s $serial shell am start -W -n com.gpsmon.app/.MainActivity > ('artifacts/' + $RunName + '-launch.log')
    Start-Sleep -Seconds 2
    & $adb -s $serial shell uiautomator dump /sdcard/gpsmon-ui.xml
    & $adb -s $serial pull /sdcard/gpsmon-ui.xml ('artifacts/' + $RunName + '-ui.xml')
    $ui = Get-Content ('artifacts/' + $RunName + '-ui.xml') -Raw
    if ($ui -notmatch 'Enable automatic monitoring' -or $ui -notmatch 'Your cars') { throw 'GPSmon launcher UI not visible' }
    & $adb -s $serial shell screencap -p /sdcard/gpsmon-verification.png
    & $adb -s $serial pull /sdcard/gpsmon-verification.png ('artifacts/' + $RunName + '.png')
    if ($results -notmatch 'PASS: GPSmon suite complete' -or $results -match 'FAIL:|Process crashed|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED') { throw 'Release tests failed. Full logs retained.' }
    & $adb -s $serial shell am instrument -w -e mode bootseed com.gpsmon.app.test/com.gpsmon.app.TripTestRunner > ('artifacts/' + $RunName + '-boot-seed.log')
    if ((Get-Content ('artifacts/' + $RunName + '-boot-seed.log') -Raw) -notmatch 'PASS: signed upgrade bootseed') { throw 'Boot fixture setup failed' }
    & $adb -s $serial shell am start -W -n com.gpsmon.app/.MainActivity > ('artifacts/' + $RunName + '-boot-before.log')
    & $adb -s $serial reboot
    Start-Sleep -Seconds 5
    $rebootDeadline = [DateTime]::UtcNow.AddMinutes(5)
    do {
        Start-Sleep -Seconds 2
        $boot = & $adb -s $serial shell getprop sys.boot_completed 2>$null
        if ([DateTime]::UtcNow -gt $rebootDeadline) { throw 'Reboot verification deadline exceeded' }
    } until ($boot -contains '1')
    $serviceDeadline = [DateTime]::UtcNow.AddSeconds(45)
    do {
        Start-Sleep -Seconds 2
        $services = & $adb -s $serial shell dumpsys activity services com.gpsmon.app
        if ([DateTime]::UtcNow -gt $serviceDeadline) { throw 'Monitoring foreground service did not restore after reboot' }
    } until (($services -join "`n") -match 'isForeground=true')
    $services | Set-Content ('artifacts/' + $RunName + '-boot-services.txt')
    & $adb -s $serial shell dumpsys location > ('artifacts/' + $RunName + '-boot-idle-location.txt')
    & $adb -s $serial shell am instrument -w -e mode bootcleanup com.gpsmon.app.test/com.gpsmon.app.TripTestRunner > ('artifacts/' + $RunName + '-boot-verify.log')
    if ((Get-Content ('artifacts/' + $RunName + '-boot-verify.log') -Raw) -notmatch 'PASS: signed upgrade bootcleanup') { throw 'Monitoring state did not survive reboot' }
    Write-Output 'PASS: real emulator reboot restored foreground monitoring'
    Write-Output ('Verified GPSmon on API ' + $Api)
} finally {
    if ($logProcess -and -not $logProcess.HasExited) { Stop-Process -Id $logProcess.Id -ErrorAction SilentlyContinue }
    if ($emulatorProcess -and -not $emulatorProcess.HasExited) {
        & $adb -s $serial emu kill
        if (-not $emulatorProcess.WaitForExit(15000)) { Stop-Process -Id $emulatorProcess.Id -ErrorAction SilentlyContinue }
    }
    $env:ANDROID_AVD_HOME = $previousAvdHome
    $env:ANDROID_SDK_ROOT = $previousSdkRoot
    $env:GPSMON_KEYSTORE_PASSWORD = $previousPassword
    $env:ANDROID_ADB_SERVER_PORT = $previousAdbPort
}
