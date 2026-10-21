param([string]$JavaHome = $env:JAVA_HOME)
$ErrorActionPreference = 'Stop'
Push-Location (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))
try {
    New-Item -ItemType Directory -Force build\network-address-tests | Out-Null
    & (Join-Path $JavaHome 'bin\javac.exe') -encoding UTF-8 -d build\network-address-tests app\src\main\java\com\oldphone\ipcam\NetUtils.java tests\network-addresses\NetUtilsTest.java
    if ($LASTEXITCODE -ne 0) { throw 'Network address test compilation failed.' }
    & (Join-Path $JavaHome 'bin\java.exe') -cp build\network-address-tests com.oldphone.ipcam.NetUtilsTest
    if ($LASTEXITCODE -ne 0) { throw 'Network address tests failed.' }
} finally { Pop-Location }
