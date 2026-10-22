param([string]$JavaHome = $env:JAVA_HOME)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
if (!$JavaHome) { $JavaHome = 'F:\SDK\jdk-17' }
$output = Join-Path $repo 'app\build\rtsp-audio-tests'
New-Item -ItemType Directory -Path $output -Force | Out-Null
$sources = @(Get-ChildItem -LiteralPath (Join-Path $repo 'tests\viewer\stubs') -Recurse -Filter '*.java' |
    Where-Object { $_.Name -ne 'H264Encoder.java' } | ForEach-Object FullName)
$sources += @('app\src\main\java\com\oldphone\ipcam\G711.java', 'app\src\main\java\com\oldphone\ipcam\RtspServer.java',
    'tests\rtsp-audio\H264Encoder.java', 'tests\viewer\RtspAudioTest.java') |
    ForEach-Object { Join-Path $repo $_ }
& (Join-Path $JavaHome 'bin\javac.exe') -encoding UTF-8 -d $output $sources
if ($LASTEXITCODE -ne 0) { throw 'RTSP audio test compilation failed' }
& (Join-Path $JavaHome 'bin\java.exe') -cp $output com.oldphone.ipcam.RtspAudioTest
if ($LASTEXITCODE -ne 0) { throw 'RTSP audio tests failed' }
