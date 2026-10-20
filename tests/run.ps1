param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$AndroidJar = $(if ($env:ANDROID_HOME) { Join-Path $env:ANDROID_HOME 'platforms\android-36\android.jar' } elseif ($env:ANDROID_SDK_ROOT) { Join-Path $env:ANDROID_SDK_ROOT 'platforms\android-36\android.jar' } else { 'F:\SDK\platforms\android-36\android.jar' })
)
$ErrorActionPreference = 'Stop'
if (!$JavaHome) { throw 'Set JAVA_HOME or pass -JavaHome with a JDK 17 directory.' }
Push-Location (Split-Path -Parent $PSScriptRoot)
try {
    & "$PSScriptRoot\output\run.ps1" -JavaHome $JavaHome -AndroidJar $AndroidJar
    if ($LASTEXITCODE -ne 0) { throw 'Output selection tests failed.' }
    & "$PSScriptRoot\geometry\run.ps1" -JavaHome $JavaHome
    if ($LASTEXITCODE -ne 0) { throw 'Geometry tests failed.' }
    & "$PSScriptRoot\camera\run.ps1" -JavaHome $JavaHome -AndroidJar $AndroidJar
    if ($LASTEXITCODE -ne 0) { throw 'Camera conversion tests failed.' }
    & (Join-Path $JavaHome 'bin\javac.exe') -encoding UTF-8 -d build\lifecycle-tests app\src\main\java\com\oldphone\ipcam\StreamLifecycle.java tests\java\com\oldphone\ipcam\StreamLifecycleTest.java
    if ($LASTEXITCODE -ne 0) { throw 'Lifecycle test compilation failed.' }
    & (Join-Path $JavaHome 'bin\java.exe') -cp build\lifecycle-tests com.oldphone.ipcam.StreamLifecycleTest
    if ($LASTEXITCODE -ne 0) { throw 'Lifecycle tests failed.' }
    & "$PSScriptRoot\codec\run.ps1" -JavaHome $JavaHome -AndroidJar $AndroidJar
    if ($LASTEXITCODE -ne 0) { throw 'Codec discovery tests failed.' }
    & "$PSScriptRoot\viewer\run.ps1" -JavaHome $JavaHome
    if ($LASTEXITCODE -ne 0) { throw 'Viewer tests failed.' }
} finally { Pop-Location }
