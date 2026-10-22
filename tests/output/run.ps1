param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$AndroidJar = 'F:\SDK\platforms\android-36\android.jar'
)
$ErrorActionPreference = 'Stop'
Push-Location (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))
try {
    New-Item -ItemType Directory -Force build\output-tests | Out-Null
    $sourceRoot = 'app\src\main\java\com\oldphone\ipcam'
    & (Join-Path $JavaHome 'bin\javac.exe') -encoding UTF-8 -cp $AndroidJar -d build\output-tests "$sourceRoot\FrameGeometry.java" "$sourceRoot\RecordingOptions.java" "$sourceRoot\DetectionLevels.java" "$sourceRoot\RecordingSchedule.java" "$sourceRoot\RecordingAudio.java" "$sourceRoot\LightPolicy.java" "$sourceRoot\Prefs.java" tests\output\PrefsOutputDefaultsTest.java
    if ($LASTEXITCODE -ne 0) { throw 'Output preference test compilation failed.' }
    & (Join-Path $JavaHome 'bin\java.exe') -cp "build\output-tests;$AndroidJar" com.oldphone.ipcam.PrefsOutputDefaultsTest
    if ($LASTEXITCODE -ne 0) { throw 'Output preference tests failed.' }
    & (Join-Path $JavaHome 'bin\javac.exe') -encoding UTF-8 -d build\output-tests "$sourceRoot\FrameGeometry.java" "$sourceRoot\OutputSizeOptions.java" tests\output\OutputSizeOptionsTest.java
    if ($LASTEXITCODE -ne 0) { throw 'Output ratio test compilation failed.' }
    & (Join-Path $JavaHome 'bin\java.exe') -cp build\output-tests com.oldphone.ipcam.OutputSizeOptionsTest
    if ($LASTEXITCODE -ne 0) { throw 'Output ratio tests failed.' }
} finally { Pop-Location }
