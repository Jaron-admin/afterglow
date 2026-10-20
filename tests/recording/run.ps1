param(
    [string]$JavaHome = 'F:\SDK\jdk-17',
    [string]$AndroidJar = 'F:\SDK\platforms\android-36\android.jar'
)
$ErrorActionPreference = 'Stop'
Push-Location (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))
try {
    $javac = Join-Path $JavaHome 'bin\javac.exe'
    $java = Join-Path $JavaHome 'bin\java.exe'
    New-Item -ItemType Directory -Force build\recording-tests | Out-Null
    $sourceRoot = 'app\src\main\java'
    & $javac -encoding UTF-8 -source 1.8 -target 1.8 -cp $AndroidJar -sourcepath $sourceRoot -d build\recording-tests tests\recording\RecordingTimelineTest.java tests\recording\RecordingStoreTest.java tests\recording\RecordingWorkQueueTest.java
    if ($LASTEXITCODE -ne 0) { throw 'Recording test compilation failed.' }
    foreach ($test in @('RecordingTimelineTest', 'RecordingStoreTest', 'RecordingWorkQueueTest')) {
        & $java -cp "build\recording-tests;$AndroidJar" "com.oldphone.ipcam.$test"
        if ($LASTEXITCODE -ne 0) { throw "Recording test failed: $test" }
    }
    New-Item -ItemType Directory -Force build\recording-native-tests | Out-Null
    $nativeStubs = @(Get-ChildItem tests\recording\stubs -Filter *.java -Recurse | ForEach-Object { $_.FullName })
    & $javac -encoding UTF-8 -source 1.8 -target 1.8 -cp $AndroidJar -sourcepath $sourceRoot -d build\recording-native-tests $nativeStubs tests\recording\RecordingManagerTest.java
    if ($LASTEXITCODE -ne 0) { throw 'Recording manager boundary test compilation failed.' }
    & $java -cp "build\recording-native-tests;$AndroidJar" com.oldphone.ipcam.RecordingManagerTest
    if ($LASTEXITCODE -ne 0) { throw 'Recording manager boundary test failed.' }
} finally { Pop-Location }
