param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$AndroidJar = 'F:\SDK\platforms\android-36\android.jar',
    [string]$FFmpeg = 'ffmpeg',
    [string]$FFprobe = 'ffprobe'
)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
if (!$JavaHome) { $JavaHome = 'F:\SDK\jdk-17' }
$output = Join-Path $repo 'app\build\rtsp-interop'
New-Item -ItemType Directory -Path $output -Force | Out-Null
$ffmpegPath = (Get-Command $FFmpeg -ErrorAction Stop).Source
$ffprobePath = (Get-Command $FFprobe -ErrorAction Stop).Source
# Compile the real H264 splitter, not the one-NAL socket-test stub.
$sources = @('app\src\main\java\com\oldphone\ipcam\RtspServer.java',
    'app\src\main\java\com\oldphone\ipcam\H264Encoder.java',
    'app\src\main\java\com\oldphone\ipcam\Nv21Converter.java',
    'tests\viewer\stubs\android\util\Base64.java',
    'tests\viewer\stubs\android\util\Log.java',
    'tests\viewer\stubs\com\oldphone\ipcam\NetUtils.java',
    'tests\rtsp-audio\FFmpegInteropTest.java') | ForEach-Object { Join-Path $repo $_ }
& (Join-Path $JavaHome 'bin\javac.exe') -encoding UTF-8 -cp $AndroidJar -d $output $sources
if ($LASTEXITCODE -ne 0) { throw 'FFmpeg interop test compilation failed' }
& (Join-Path $JavaHome 'bin\java.exe') -cp "$output;$AndroidJar" com.oldphone.ipcam.FFmpegInteropTest $output $ffmpegPath $ffprobePath
if ($LASTEXITCODE -ne 0) { throw 'FFmpeg interop tests failed' }
