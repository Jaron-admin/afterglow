param(
    [string]$Sdk = $(if ($env:ANDROID_HOME) { $env:ANDROID_HOME } elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { 'F:\SDK' }),
    [string]$Jdk = $(if ($env:JAVA_HOME) { $env:JAVA_HOME } else { 'F:\SDK\jdk-17' }),
    [string]$BuildTools = '36.0.0',
    [string]$Keystore = (Join-Path $env:USERPROFILE '.android\debug.keystore')
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
Set-Location -LiteralPath $PSScriptRoot

function Invoke-Tool([string]$Executable, [string[]]$Arguments) {
    & $Executable @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Executable failed (exit $LASTEXITCODE)" }
}

$toolDir = Join-Path $Sdk "build-tools\$BuildTools"
$androidJar = Join-Path $Sdk 'platforms\android-36\android.jar'
$java = Join-Path $Jdk 'bin\java.exe'
$javac = Join-Path $Jdk 'bin\javac.exe'
$jar = Join-Path $Jdk 'bin\jar.exe'
$aapt = Join-Path $toolDir 'aapt2.exe'
foreach ($required in @($androidJar, $java, $javac, $jar, $aapt, (Join-Path $toolDir 'lib\d8.jar'), (Join-Path $toolDir 'lib\apksigner.jar'))) {
    if (!(Test-Path -LiteralPath $required)) { throw "Required tool missing: $required" }
}

# Each build gets a fresh directory; never recursively remove a computed path.
$out = Join-Path $PSScriptRoot ('build\manual-' + [guid]::NewGuid().ToString('N'))
foreach ($folder in @('', 'gen', 'classes', 'dex')) {
    New-Item -ItemType Directory -Path (Join-Path $out $folder) -Force | Out-Null
}
$gradleText = Get-Content -LiteralPath 'app\build.gradle.kts' -Raw
$version = [regex]::Match($gradleText, 'versionName\s*=\s*"([^"]+)"').Groups[1].Value
$versionCode = [regex]::Match($gradleText, 'versionCode\s*=\s*(\d+)').Groups[1].Value
$namespace = [regex]::Match($gradleText, 'namespace\s*=\s*"([^"]+)"').Groups[1].Value
if (!$version -or !$versionCode -or !$namespace) { throw 'Missing version or namespace in app/build.gradle.kts' }
[xml]$manifest = Get-Content -LiteralPath 'app\src\main\AndroidManifest.xml' -Raw
$manifest.DocumentElement.SetAttribute('package', $namespace)
$manifest.Save((Join-Path $out 'AndroidManifest.xml'))

Write-Output '[1/6] Compile resources'
Invoke-Tool $aapt @('compile', '--dir', 'app\src\main\res', '-o', "$out\resources.zip")
Write-Output '[2/6] Link resources'
Invoke-Tool $aapt @('link', '-o', "$out\base.apk", '-I', $androidJar, '--manifest', "$out\AndroidManifest.xml", '--java', "$out\gen", '--min-sdk-version', '19', '--target-sdk-version', '33', '--version-code', $versionCode, '--version-name', $version, '--auto-add-overlay', "$out\resources.zip")
Write-Output '[3/6] Compile Java'
$sources = @(Get-ChildItem -LiteralPath 'app\src\main\java', "$out\gen" -Recurse -Filter '*.java' -File | ForEach-Object { '"' + $_.FullName.Replace('\', '/') + '"' })
[IO.File]::WriteAllLines("$out\sources.txt", $sources, [Text.UTF8Encoding]::new($false))
Invoke-Tool $javac @('-J-Duser.language=en', '-J-Duser.country=US', '-encoding', 'UTF-8', '-source', '8', '-target', '8', '-bootclasspath', $androidJar, '-d', "$out\classes", '-Xlint:-options', "@$out\sources.txt")
Write-Output '[4/6] Convert to DEX'
Invoke-Tool $jar @('cf', "$out\classes.jar", '-C', "$out\classes", '.')
Invoke-Tool $java @('-cp', "$toolDir\lib\d8.jar", 'com.android.tools.r8.D8', '--min-api', '19', '--lib', $androidJar, '--output', "$out\dex", "$out\classes.jar")
Write-Output '[5/6] Package and align'
Copy-Item -LiteralPath "$out\base.apk" -Destination "$out\unsigned.apk"
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [IO.Compression.ZipFile]::Open("$out\unsigned.apk", [IO.Compression.ZipArchiveMode]::Update)
try {
    [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive, "$out\dex\classes.dex", 'classes.dex', [IO.Compression.CompressionLevel]::Optimal) | Out-Null
} finally { $archive.Dispose() }
Invoke-Tool (Join-Path $toolDir 'zipalign.exe') @('-f', '-p', '4', "$out\unsigned.apk", "$out\aligned.apk")

Write-Output '[6/6] Sign and verify'
if (!(Test-Path -LiteralPath $Keystore)) {
    throw "Debug keystore missing: $Keystore. Build once with Gradle or supply -Keystore. Never commit a signing key."
}
New-Item -ItemType Directory -Path 'dist' -Force | Out-Null
$apk = Join-Path $PSScriptRoot "dist\Afterglow-$version.apk"
Invoke-Tool $java @('-jar', "$toolDir\lib\apksigner.jar", 'sign', '--ks', $Keystore, '--ks-pass', 'pass:android', '--key-pass', 'pass:android', '--ks-key-alias', 'androiddebugkey', '--out', $apk, "$out\aligned.apk")
Invoke-Tool $java @('-jar', "$toolDir\lib\apksigner.jar", 'verify', '--verbose', $apk)
Get-Item -LiteralPath $apk | Select-Object FullName, Length
