param([string]$JavaHome = $env:JAVA_HOME)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
if (!$JavaHome) { $JavaHome = 'F:\SDK\jdk-17' }
$output = Join-Path $repo 'app\build\viewer-tests'
New-Item -ItemType Directory -Path $output -Force | Out-Null
$sources = @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'stubs') -Recurse -Filter '*.java' | ForEach-Object FullName)
$sources += @('app\src\main\java\com\oldphone\ipcam\MjpegServer.java', 'app\src\main\java\com\oldphone\ipcam\RecordingHttpApi.java', 'app\src\main\java\com\oldphone\ipcam\MjpegClient.java', 'app\src\main\java\com\oldphone\ipcam\RtspServer.java', 'tests\viewer\ViewerSocketTest.java', 'tests\viewer\RtspLifecycleTest.java', 'tests\viewer\RecordingHttpTest.java', 'tests\viewer\MjpegDemandTest.java') | ForEach-Object { Join-Path $repo $_ }
& (Join-Path $JavaHome 'bin\javac.exe') -encoding UTF-8 -d $output $sources
if ($LASTEXITCODE -ne 0) { throw 'Viewer test compilation failed' }
$page = Join-Path $output 'viewer.html'
& (Join-Path $JavaHome 'bin\java.exe') -cp $output com.oldphone.ipcam.ViewerSocketTest $page
$socketExit = $LASTEXITCODE
& (Join-Path $JavaHome 'bin\java.exe') -cp $output com.oldphone.ipcam.RtspLifecycleTest
$rtspExit = $LASTEXITCODE
& (Join-Path $JavaHome 'bin\java.exe') -cp $output com.oldphone.ipcam.RecordingHttpTest
$recordingsExit = $LASTEXITCODE
& (Join-Path $JavaHome 'bin\java.exe') -cp $output com.oldphone.ipcam.MjpegDemandTest
$demandExit = $LASTEXITCODE
& node (Join-Path $PSScriptRoot 'browser.test.js') $page
if ($socketExit -ne 0 -or $rtspExit -ne 0 -or $recordingsExit -ne 0 -or $demandExit -ne 0 -or $LASTEXITCODE -ne 0) { throw 'Viewer regression tests failed' }
