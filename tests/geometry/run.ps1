param([string]$JavaHome = $env:JAVA_HOME)
$ErrorActionPreference = 'Stop'
Push-Location (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))
try {
    & (Join-Path $JavaHome 'bin\javac.exe') -encoding UTF-8 -d build\geometry-tests app\src\main\java\com\oldphone\ipcam\FrameGeometry.java app\src\main\java\com\oldphone\ipcam\Nv21Transform.java app\src\main\java\com\oldphone\ipcam\YuvRotator.java tests\geometry\FrameGeometryTest.java
    if ($LASTEXITCODE -ne 0) { throw 'Geometry compilation failed.' }
    & (Join-Path $JavaHome 'bin\java.exe') -cp build\geometry-tests com.oldphone.ipcam.FrameGeometryTest
    if ($LASTEXITCODE -ne 0) { throw 'Geometry tests failed.' }
    & (Join-Path $JavaHome 'bin\javac.exe') -encoding UTF-8 -d build\geometry-tests app\src\main\java\com\oldphone\ipcam\OrientationStability.java tests\geometry\OrientationStabilityTest.java
    if ($LASTEXITCODE -ne 0) { throw 'Orientation test compilation failed.' }
    & (Join-Path $JavaHome 'bin\java.exe') -cp build\geometry-tests com.oldphone.ipcam.OrientationStabilityTest
    if ($LASTEXITCODE -ne 0) { throw 'Orientation tests failed.' }
} finally { Pop-Location }
