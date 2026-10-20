param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$AndroidJar = 'F:\SDK\platforms\android-36\android.jar'
)
$ErrorActionPreference = 'Stop'
if (!$JavaHome) { throw 'Set JAVA_HOME or pass -JavaHome with a JDK 17 directory.' }
if (!(Test-Path -LiteralPath $AndroidJar)) { throw "Android compile jar not found: $AndroidJar" }
Push-Location (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))
try {
    $javac = Join-Path $JavaHome 'bin\javac.exe'
    $java = Join-Path $JavaHome 'bin\java.exe'
    # Compile production against the real SDK first, so discovery stubs cannot
    # accidentally hide mistakes in the actual MediaCodec API usage.
    & $javac -encoding UTF-8 -cp $AndroidJar -d build\codec-test-production app\src\main\java\com\oldphone\ipcam\H264Encoder.java app\src\main\java\com\oldphone\ipcam\Nv21Converter.java
    if ($LASTEXITCODE -ne 0) { throw 'Production encoder compilation failed.' }
    $codecTests = @(Get-ChildItem $PSScriptRoot -Recurse -Filter *.java | Select-Object -ExpandProperty FullName)
    & $javac -encoding UTF-8 -cp "build\codec-test-production;$AndroidJar" -d build\codec-tests $codecTests
    if ($LASTEXITCODE -ne 0) { throw 'Codec discovery test compilation failed.' }
    foreach ($apiLevel in @(19, 21)) {
        & $java "-Dandroid.sdk=$apiLevel" -cp "build\codec-tests;build\codec-test-production;$AndroidJar" com.oldphone.ipcam.CodecDiscoveryTest
        if ($LASTEXITCODE -ne 0) { throw "Codec discovery test failed on API $apiLevel" }
    }
} finally { Pop-Location }
