param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$AndroidJar = $(if ($env:ANDROID_HOME) { Join-Path $env:ANDROID_HOME 'platforms\android-36\android.jar' } elseif ($env:ANDROID_SDK_ROOT) { Join-Path $env:ANDROID_SDK_ROOT 'platforms\android-36\android.jar' } else { 'F:\SDK\platforms\android-36\android.jar' })
)
$ErrorActionPreference = 'Stop'
Push-Location (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))
try {
    New-Item -ItemType Directory -Force build\discovery-tests | Out-Null
    $sourceRoot = 'app\src\main\java\com\oldphone\ipcam'
    & (Join-Path $JavaHome 'bin\javac.exe') -encoding UTF-8 -cp $AndroidJar -d build\discovery-tests "$sourceRoot\FrameGeometry.java" "$sourceRoot\RecordingOptions.java" "$sourceRoot\DetectionLevels.java" "$sourceRoot\RecordingSchedule.java" "$sourceRoot\Prefs.java" tests\discovery\DeviceIdentityTest.java
    if ($LASTEXITCODE -ne 0) { throw 'Device identity test compilation failed.' }
    & (Join-Path $JavaHome 'bin\java.exe') -cp "build\discovery-tests;$AndroidJar" com.oldphone.ipcam.DeviceIdentityTest
    if ($LASTEXITCODE -ne 0) { throw 'Device identity tests failed.' }
    & (Join-Path $JavaHome 'bin\javac.exe') -encoding UTF-8 -d build\discovery-tests "$sourceRoot\NsdRegistration.java" tests\discovery\NsdRegistrationTest.java
    if ($LASTEXITCODE -ne 0) { throw 'NSD lifecycle test compilation failed.' }
    & (Join-Path $JavaHome 'bin\java.exe') -cp build\discovery-tests com.oldphone.ipcam.NsdRegistrationTest
    if ($LASTEXITCODE -ne 0) { throw 'NSD lifecycle tests failed.' }
    & (Join-Path $JavaHome 'bin\javac.exe') -encoding UTF-8 -cp "build\discovery-tests;$AndroidJar" -d build\discovery-tests "$sourceRoot\ServiceDiscovery.java"
    if ($LASTEXITCODE -ne 0) { throw 'Android NSD adapter compilation failed.' }
    & (Join-Path $JavaHome 'bin\javac.exe') -encoding UTF-8 -cp $AndroidJar -d build\discovery-tests "$sourceRoot\NetUtils.java" "$sourceRoot\LanScanner.java" tests\discovery\LanScannerTest.java
    if ($LASTEXITCODE -ne 0) { throw 'LAN scanner test compilation failed.' }
    & (Join-Path $JavaHome 'bin\java.exe') -cp "build\discovery-tests;$AndroidJar" com.oldphone.ipcam.LanScannerTest
    if ($LASTEXITCODE -ne 0) { throw 'LAN scanner tests failed.' }
} finally { Pop-Location }
