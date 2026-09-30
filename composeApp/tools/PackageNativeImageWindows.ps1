param(
    [Parameter(Mandatory = $true)][string]$RuntimeDirectory,
    [Parameter(Mandatory = $true)][string]$OutputArchive
)

$ErrorActionPreference = 'Stop'
$runtimePath = (Resolve-Path -LiteralPath $RuntimeDirectory).Path
$archivePath = [IO.Path]::GetFullPath($OutputArchive)
$archiveDirectory = Split-Path -Parent $archivePath
New-Item -ItemType Directory -Force -Path $archiveDirectory | Out-Null

# Publish only runtime files. Metadata, profiles, logs, reports and the
# instrumented training executable must stay outside the portable archive.
$required = @('NCMConverter4a.exe', 'awt.dll', 'jawt.dll', 'fontmanager.dll',
    'java.dll', 'jvm.dll', 'msvcp140.dll', 'vcruntime140.dll', 'vcruntime140_1.dll',
    'bin/jawt.dll', 'lib/fontconfig.bfc')
foreach ($relativePath in $required) {
    $file = Join-Path $runtimePath $relativePath
    if (-not (Test-Path -LiteralPath $file -PathType Leaf)) {
        throw "Native Image runtime file is missing: $file"
    }
}
$files = @('NCMConverter4a.exe') + @(
    Get-ChildItem -LiteralPath $runtimePath -Filter '*.dll' -File | ForEach-Object Name
) + @('bin/jawt.dll', 'lib/fontconfig.bfc')

Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$temporaryArchive = Join-Path $archiveDirectory ('.native-image-' + [guid]::NewGuid() + '.zip')
$archive = [IO.Compression.ZipFile]::Open($temporaryArchive, [IO.Compression.ZipArchiveMode]::Create)
try {
    foreach ($relativePath in $files) {
        $entryName = 'NCMConverter4a/' + $relativePath.Replace('\', '/')
        [IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
            $archive, (Join-Path $runtimePath $relativePath), $entryName,
            [IO.Compression.CompressionLevel]::Optimal
        ) | Out-Null
    }
} finally {
    $archive.Dispose()
}
Move-Item -LiteralPath $temporaryArchive -Destination $archivePath -Force
Get-Item -LiteralPath $archivePath | Select-Object FullName,Length
