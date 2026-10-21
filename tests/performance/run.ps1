param([string]$JavaHome = $env:JAVA_HOME)
$ErrorActionPreference = 'Stop'
Push-Location (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))
try {
    New-Item -ItemType Directory -Force build\performance-tests | Out-Null
    & (Join-Path $JavaHome 'bin\javac.exe') -encoding UTF-8 -d build\performance-tests app\src\main\java\com\oldphone\ipcam\FramePacer.java app\src\main\java\com\oldphone\ipcam\MjpegCadence.java tests\performance\FramePacerTest.java tests\performance\FramePacerSimulation.java tests\performance\MjpegCadenceTest.java
    if ($LASTEXITCODE -ne 0) { throw 'Frame pacing test compilation failed.' }
    & (Join-Path $JavaHome 'bin\java.exe') -cp build\performance-tests com.oldphone.ipcam.FramePacerTest
    if ($LASTEXITCODE -ne 0) { throw 'Frame pacing tests failed.' }
    & (Join-Path $JavaHome 'bin\java.exe') -cp build\performance-tests com.oldphone.ipcam.MjpegCadenceTest
    if ($LASTEXITCODE -ne 0) { throw 'MJPEG cadence tests failed.' }
    & (Join-Path $JavaHome 'bin\java.exe') -cp build\performance-tests com.oldphone.ipcam.FramePacerSimulation
    if ($LASTEXITCODE -ne 0) { throw 'Frame pacing simulation failed.' }
} finally { Pop-Location }
