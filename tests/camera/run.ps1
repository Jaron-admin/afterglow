param(
    [string]$JavaHome = 'F:\SDK\jdk-17',
    [string]$AndroidJar = 'F:\SDK\platforms\android-36\android.jar'
)
$ErrorActionPreference = 'Stop'
Push-Location (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))
try {
    $javac = Join-Path $JavaHome 'bin\javac.exe'
    $java = Join-Path $JavaHome 'bin\java.exe'
    New-Item -ItemType Directory -Force build\camera-tests | Out-Null
    New-Item -ItemType Directory -Force build\camera-production | Out-Null
    $sourceRoot = 'app\src\main\java'
    $production = @('CameraCatalog', 'CameraCapture', 'Camera2Backend', 'LegacyCameraBackend', 'Yuv420Converter') | ForEach-Object { "$sourceRoot\com\oldphone\ipcam\$_.java" }
    & $javac -encoding UTF-8 -source 1.8 -target 1.8 -cp $AndroidJar -sourcepath $sourceRoot -d build\camera-production $production
    if ($LASTEXITCODE -ne 0) { throw 'Production camera compilation failed.' }
    & $javac -encoding UTF-8 -cp "build\camera-production;$AndroidJar" -d build\camera-tests tests\camera\Yuv420ConverterTest.java tests\camera\CameraSourceSizeTest.java tests\camera\CameraReservationTest.java
    if ($LASTEXITCODE -ne 0) { throw 'Camera test compilation failed.' }
    foreach ($test in @('Yuv420ConverterTest', 'CameraSourceSizeTest', 'CameraReservationTest')) {
        & $java -cp "build\camera-tests;build\camera-production;$AndroidJar" "com.oldphone.ipcam.$test"
        if ($LASTEXITCODE -ne 0) { throw "Camera test failed: $test" }
    }
} finally { Pop-Location }
