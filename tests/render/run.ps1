param(
    [string]$JavaHome = $env:JAVA_HOME
)
$ErrorActionPreference = 'Stop'
if (!$JavaHome) { throw 'Set JAVA_HOME or pass -JavaHome with a JDK 17 directory.' }
Push-Location (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))
try {
    $javac = Join-Path $JavaHome 'bin\javac.exe'
    $java = Join-Path $JavaHome 'bin\java.exe'
    New-Item -ItemType Directory -Force build\render-tests | Out-Null
    & $javac -encoding UTF-8 -source 8 -target 8 -d build\render-tests tests\render\RenderBudgetTest.java
    if ($LASTEXITCODE -ne 0) { throw 'Render budget test compilation failed.' }
    & $java -cp build\render-tests com.oldphone.ipcam.RenderBudgetTest
    if ($LASTEXITCODE -ne 0) { throw 'Render budget test failed.' }
} finally { Pop-Location }
