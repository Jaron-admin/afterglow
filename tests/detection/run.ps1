param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$AndroidJar = 'F:\SDK\platforms\android-36\android.jar'
)
$ErrorActionPreference = 'Stop'
Push-Location (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))
try {
    $sources = @(
        'app\src\main\java\com\oldphone\ipcam\MotionDetector.java',
        'app\src\main\java\com\oldphone\ipcam\SoundDetector.java',
        'tests\detection\DetectionTest.java'
    )
    & (Join-Path $JavaHome 'bin\javac.exe') -encoding UTF-8 -d build\detection-tests @sources
    if ($LASTEXITCODE -ne 0) { throw 'Detection compilation failed.' }
    & (Join-Path $JavaHome 'bin\java.exe') -cp build\detection-tests com.oldphone.ipcam.DetectionTest
    if ($LASTEXITCODE -ne 0) { throw 'Detection tests failed.' }
    $stubs = @(Get-ChildItem tests\detection\stubs -Recurse -Filter *.java | ForEach-Object FullName)
    & (Join-Path $JavaHome 'bin\javac.exe') -encoding UTF-8 -d build\audio-tests @stubs app\src\main\java\com\oldphone\ipcam\AudioCapture.java tests\detection\AudioCaptureTest.java
    if ($LASTEXITCODE -ne 0) { throw 'Audio lifecycle test compilation failed.' }
    & (Join-Path $JavaHome 'bin\java.exe') -cp build\audio-tests com.oldphone.ipcam.AudioCaptureTest
    if ($LASTEXITCODE -ne 0) { throw 'Audio lifecycle tests failed.' }
    & (Join-Path $JavaHome 'bin\javac.exe') -encoding UTF-8 -cp $AndroidJar -d build\audio-android-check app\src\main\java\com\oldphone\ipcam\AudioCapture.java
    if ($LASTEXITCODE -ne 0) { throw 'Audio Android API compilation failed.' }
}
finally { Pop-Location }
