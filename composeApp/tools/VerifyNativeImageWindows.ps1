param(
    [Parameter(Mandatory = $true)][string]$ArchivePath,
    [switch]$CheckRuntime
)

$ErrorActionPreference = 'Stop'
$archive = (Resolve-Path -LiteralPath $ArchivePath).Path
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [IO.Compression.ZipFile]::OpenRead($archive)
try {
    $required = @('NCMConverter4a.exe', 'awt.dll', 'jawt.dll', 'fontmanager.dll', 'freetype.dll',
        'javajpeg.dll', 'lcms.dll',
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
if ($CheckRuntime) {
    # Keep diagnostics, but never reuse the developer's home or a previous test cache.
    # Skiko adds its own hash directory; a deeply nested artifact directory can
    # exceed Windows native loader limits before any application code is tested.
    $validationRoot = Join-Path ([IO.Path]::GetTempPath()) ('ncm-smoke-' + [guid]::NewGuid().ToString('N').Substring(0, 8))
    $folder = '' + [char]0x4FBF + [char]0x643A + ' ' + [char]0x8FD0 + [char]0x884C
    $destination = Join-Path $validationRoot $folder
    New-Item -ItemType Directory -Path $destination | Out-Null
    Write-Host "Windows runtime diagnostics: $validationRoot"
    [IO.Compression.ZipFile]::ExtractToDirectory($archive, $destination)
    $runtime = Join-Path $destination 'NCMConverter4a'
    $savedEnvironment = @{}
    foreach ($name in @('APPDATA', 'LOCALAPPDATA', 'JAVA_HOME', 'PATH', 'TEMP', 'TMP',
            'SKIKO_RENDER_API', 'JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS')) {
        $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name)
    }
    try {
        $env:JAVA_HOME = $null
        $env:PATH = "$env:SystemRoot\System32;$env:SystemRoot"
        foreach ($name in @('SKIKO_RENDER_API', 'JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS')) {
            Remove-Item -LiteralPath "Env:$name" -ErrorAction SilentlyContinue
        }
        foreach ($test in @('runtime', 'startup-default', 'startup-software', 'startup-software-empty', 'file-picker', 'conversion')) {
            $profileRoot = Join-Path $validationRoot $test
            $profileHome = Join-Path $profileRoot 'home'
            $profileTemp = Join-Path $profileRoot 'temp'
            $profileCache = Join-Path $profileRoot 'skiko'
            # Windows Shell uses the real login profile; isolate Java's user.home
            # without rewriting USERPROFILE and breaking known-folder resolution.
            $env:APPDATA = Join-Path $profileRoot 'appdata'
            $env:LOCALAPPDATA = Join-Path $profileRoot 'localappdata'
            $env:TEMP = $profileTemp
            $env:TMP = $profileTemp
            New-Item -ItemType Directory -Force $profileHome,$profileTemp,$profileCache,$env:APPDATA,$env:LOCALAPPDATA | Out-Null
            if ($test -eq 'startup-software-empty') {
                # Reproduce the empty value preserved by PowerShell 7/.NET 9+,
                # then verify that the child receives a genuinely unset override.
                [Environment]::SetEnvironmentVariable('SKIKO_RENDER_API', '')
            }
            Remove-Item -LiteralPath 'Env:SKIKO_RENDER_API' -ErrorAction SilentlyContinue
            if ($test -like 'startup-software*') {
                $settings = Join-Path $env:APPDATA 'NCMConverter4a'
                New-Item -ItemType Directory -Path $settings | Out-Null
                'gpuRendering=false' | Set-Content -LiteralPath (Join-Path $settings 'rendering.properties') -Encoding ascii
            }
            $flag = switch ($test) {
                'runtime' { '--runtime-smoke' }
                'file-picker' { '--file-picker-smoke' }
                'conversion' { '--pgo-train' }
                default { '--startup-smoke' }
            }
            $marker = switch ($test) {
                'runtime' { 'Native Image runtime smoke test passed' }
                'file-picker' { 'File picker smoke test passed' }
                'conversion' { 'PGO conversion training passed' }
                default { 'Compose desktop startup smoke test passed' }
            }
            $stdout = Join-Path $profileRoot 'stdout.log'
            $stderr = Join-Path $profileRoot 'stderr.log'
            Write-Host "Checking $test with APPDATA=$env:APPDATA"
            $arguments = @("`"-Duser.home=$profileHome`"", "`"-Djava.io.tmpdir=$profileTemp`"",
                "`"-Dskiko.data.path=$profileCache`"", $flag)
            # Run outside the checkout so local native/build files cannot mask omissions.
            $process = Start-Process -FilePath (Join-Path $runtime 'NCMConverter4a.exe') -WorkingDirectory $runtime -ArgumentList $arguments -WindowStyle Hidden -PassThru -RedirectStandardOutput $stdout -RedirectStandardError $stderr
            $processHandle = $process.Handle
            if (-not $process.WaitForExit(45000)) {
                $process.Kill()
                throw "Packaged $test check timed out; see $profileRoot"
            }
            $process.WaitForExit()
            $process.Refresh()
            Get-Content -LiteralPath $stdout
            Get-Content -LiteralPath $stderr
            if ($process.ExitCode -ne 0 -or -not (Select-String -LiteralPath $stdout -Pattern $marker -SimpleMatch -Quiet)) {
                throw "Packaged $test check failed (exit $($process.ExitCode)); see $profileRoot"
            }
            if (Select-String -LiteralPath $stderr -Pattern 'Exception|Error|FATAL' -Quiet) {
                throw "Packaged $test reported a runtime error; see $stderr"
            }
            if ($test -like 'startup-software*' -and -not (Select-String -LiteralPath $stdout -Pattern 'Skiko renderer: SOFTWARE' -SimpleMatch -Quiet)) {
                throw 'Packaged startup ignored the software rendering preference'
            }
            if ($test -eq 'runtime' -or $test -like 'startup-*') {
                $icu = @(Get-ChildItem -LiteralPath $profileCache -Recurse -File -Filter 'icudtl.dat')
                if ($icu.Count -ne 1 -or $icu[0].Length -eq 0) {
                    throw "Packaged $test did not extract ICU data to its empty cache"
                }
            }
            Write-Host "Packaged check passed: $test"
        }
    } finally {
        foreach ($name in $savedEnvironment.Keys) {
            if ($null -eq $savedEnvironment[$name]) {
                Remove-Item -LiteralPath "Env:$name" -ErrorAction SilentlyContinue
            } else {
                [Environment]::SetEnvironmentVariable($name, $savedEnvironment[$name])
            }
        }
    }
}
Get-FileHash -LiteralPath $archive -Algorithm SHA256
Get-Item -LiteralPath $archive | Select-Object FullName,Length
