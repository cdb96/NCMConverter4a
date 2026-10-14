param([Parameter(Mandatory = $true)][string]$ArchivePath)

$ErrorActionPreference = 'Stop'
$archive = (Resolve-Path -LiteralPath $ArchivePath).Path
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [IO.Compression.ZipFile]::OpenRead($archive)
try {
    $required = @('NCMConverter4a.exe', 'awt.dll', 'jawt.dll', 'fontmanager.dll',
        'java.dll', 'jvm.dll', 'msvcp140.dll', 'vcruntime140.dll', 'vcruntime140_1.dll',
        'bin/jawt.dll', 'lib/fontconfig.bfc')
    foreach ($relativePath in $required) {
        $entry = $zip.GetEntry("NCMConverter4a/$relativePath")
        if (-not $entry -or $entry.Length -eq 0) {
            throw "Required runtime file is missing or empty: $relativePath"
        }
    }
    foreach ($entry in $zip.Entries) {
        if ($entry.FullName -notmatch '^NCMConverter4a/(NCMConverter4a\.exe|[^/]+\.dll|bin/jawt\.dll|lib/fontconfig\.bfc)$') {
            throw "Unexpected file in portable package: $($entry.FullName)"
        }
    }
} finally {
    $zip.Dispose()
}
Write-Host 'Windows package contents verified'
Get-FileHash -LiteralPath $archive -Algorithm SHA256
Get-Item -LiteralPath $archive | Select-Object FullName,Length
