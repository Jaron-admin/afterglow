param([string]$JavaHome = $env:JAVA_HOME)
$ErrorActionPreference = 'Stop'
Push-Location (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))
try {
    New-Item -ItemType Directory -Force build/local-preview-tests | Out-Null
    $sources = @(Get-ChildItem -LiteralPath "$PSScriptRoot/stubs" -Recurse -Filter '*.java' | ForEach-Object FullName)
    $root = 'app/src/main/java/com/oldphone/ipcam'
    $sources += @("$root/JpegFrameDecoder.java", "$root/LocalPreviewDecoder.java", "$root/PreviewFrame.java", "$root/FrameGeometry.java", "$PSScriptRoot/LocalPreviewTest.java")
    & (Join-Path $JavaHome 'bin/javac.exe') -encoding UTF-8 -d build/local-preview-tests $sources
    if ($LASTEXITCODE -ne 0) { throw 'Local preview test compilation failed.' }
    & (Join-Path $JavaHome 'bin/java.exe') -cp build/local-preview-tests com.oldphone.ipcam.LocalPreviewTest
    if ($LASTEXITCODE -ne 0) { throw 'Local preview tests failed.' }
} finally { Pop-Location }
