param(
    [Parameter(Mandatory = $true)][string]$ArchivePath,
    [Parameter(Mandatory = $true)][string]$ReportDirectory
)

$ErrorActionPreference = 'Stop'
$archive = (Resolve-Path -LiteralPath $ArchivePath).Path
$report = [IO.Path]::GetFullPath($ReportDirectory)
New-Item -ItemType Directory -Force -Path $report | Out-Null
$root = Join-Path ([IO.Path]::GetTempPath()) ('ncm-first-' + [guid]::NewGuid().ToString('N').Substring(0, 8))
$folder = '' + [char]0x4FBF + [char]0x643A + ' ' + [char]0x8FD0 + [char]0x884C
$destination = Join-Path $root $folder
New-Item -ItemType Directory -Path $destination | Out-Null
Add-Type -AssemblyName System.IO.Compression.FileSystem
[IO.Compression.ZipFile]::ExtractToDirectory($archive, $destination)
$runtime = Join-Path $destination 'NCMConverter4a'
$executable = Join-Path $runtime 'NCMConverter4a.exe'
$environmentNames = @('APPDATA', 'LOCALAPPDATA', 'USERPROFILE', 'HOME', 'JAVA_HOME', 'PATH', 'TEMP', 'TMP',
    'SKIKO_RENDER_API', 'JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS')
$savedEnvironment = @{}
foreach ($name in $environmentNames) { $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name) }
$checks = @()
$failure = $null
$javaAvailable = $null
try {
    foreach ($name in @('JAVA_HOME', 'SKIKO_RENDER_API', 'JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS')) {
        Remove-Item -LiteralPath "Env:$name" -ErrorAction SilentlyContinue
    }
    $env:PATH = "$env:SystemRoot\System32;$env:SystemRoot"
    $java = @(Get-Command -Name java.exe -CommandType Application -All -ErrorAction SilentlyContinue)
    $javaAvailable = $java.Count -ne 0
    if ($javaAvailable) { throw "Java is still accessible through isolated PATH: $($java.Source)" }
    foreach ($mode in @('default', 'software')) {
        $profile = Join-Path $root $mode
        $userDirectory = Join-Path $profile 'home'
        $temporary = Join-Path $profile 'temp'
        $env:APPDATA = Join-Path $profile 'appdata'
        $env:LOCALAPPDATA = Join-Path $profile 'localappdata'
        $env:USERPROFILE = $userDirectory
        $env:HOME = $userDirectory
        $env:TEMP = $temporary
        $env:TMP = $temporary
        New-Item -ItemType Directory -Path $userDirectory,$temporary,$env:APPDATA,$env:LOCALAPPDATA | Out-Null
        if (@(Get-ChildItem -LiteralPath $profile -Recurse -File).Count -ne 0) { throw 'First-start profile was not empty' }
        if ($mode -eq 'software') {
            $settings = Join-Path $env:APPDATA 'NCMConverter4a'
            New-Item -ItemType Directory -Path $settings | Out-Null
            'gpuRendering=false' | Set-Content -LiteralPath (Join-Path $settings 'rendering.properties') -Encoding ascii
        }
        $stdout = Join-Path $report "$mode-stdout.log"
        $stderr = Join-Path $report "$mode-stderr.log"
        $modules = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
        $process = $null
        try {
            # Launch the ordinary entry point: no smoke flag and no Skiko cache override.
            $arguments = @("`"-Duser.home=$userDirectory`"", "`"-Djava.io.tmpdir=$temporary`"")
            $process = Start-Process -FilePath $executable -WorkingDirectory $runtime -ArgumentList $arguments -WindowStyle Hidden -PassThru -RedirectStandardOutput $stdout -RedirectStandardError $stderr
            $processHandle = $process.Handle
            $timer = [Diagnostics.Stopwatch]::StartNew()
            $readyAt = $null
            while ($timer.ElapsedMilliseconds -lt 30000) {
                if ($process.HasExited) { throw "Ordinary $mode startup exited early ($($process.ExitCode))" }
                $process.Refresh()
                foreach ($module in $process.Modules) { [void]$modules.Add($module.FileName) }
                if ($process.MainWindowHandle -ne 0 -and $process.MainWindowTitle -eq 'NCMConverter4a' -and $process.Responding) {
                    if ($null -eq $readyAt) { $readyAt = $timer.ElapsedMilliseconds }
                    if ($timer.ElapsedMilliseconds - $readyAt -ge 3000) { break }
                }
                Start-Sleep -Milliseconds 50
            }
            if ($null -eq $readyAt -or $timer.ElapsedMilliseconds - $readyAt -lt 3000) { throw 'A responsive main window did not remain available' }
            $unexpected = @($modules | Where-Object {
                -not $_.StartsWith($runtime + '\', [StringComparison]::OrdinalIgnoreCase) -and
                -not $_.StartsWith($profile + '\', [StringComparison]::OrdinalIgnoreCase) -and
                -not $_.StartsWith($env:SystemRoot + '\', [StringComparison]::OrdinalIgnoreCase)
            })
            if ($unexpected.Count) { throw "DLLs loaded from outside the package, empty profile or Windows: $($unexpected -join ', ')" }
            foreach ($name in @('msvcp140.dll', 'vcruntime140.dll', 'vcruntime140_1.dll', 'awt.dll', 'fontmanager.dll', 'java.dll', 'jvm.dll')) {
                $loaded = @($modules | Where-Object { [IO.Path]::GetFileName($_) -ieq $name })
                if ($loaded.Count -ne 1 -or -not $loaded[0].StartsWith($runtime + '\', [StringComparison]::OrdinalIgnoreCase)) {
                    throw "The packaged runtime DLL was not used: $name ($loaded)"
                }
            }
            foreach ($library in @(
                @{Name='Skiko';Pattern='^skiko-windows-x64\.dll$'},
                @{Name='ncmc4a';Pattern='^[a-f0-9]{32}\.dll$'}
            )) {
                $loaded = @($modules | Where-Object {
                    [IO.Path]::GetFileName($_) -match $library.Pattern -and $_.StartsWith($profile + '\', [StringComparison]::OrdinalIgnoreCase)
                })
                if ($loaded.Count -eq 0) { throw "Native library was not loaded from the initially empty profile: $($library.Name)" }
            }
            $icu = @(Get-ChildItem -LiteralPath $profile -Recurse -File -Filter icudtl.dat)
            if ($icu.Count -ne 1 -or $icu[0].Length -eq 0) { throw 'First startup did not extract exactly one nonempty ICU resource' }
            $icuHash = (Get-FileHash -LiteralPath $icu[0].FullName -Algorithm SHA256).Hash.ToLowerInvariant()
            $expectedCache = Join-Path $userDirectory ".ncmconverter4a\skiko\icu-$($icuHash.Substring(0, 16))"
            if (-not $icu[0].FullName.StartsWith($expectedCache + '\', [StringComparison]::OrdinalIgnoreCase)) {
                throw 'Default startup did not use the application-specific, ICU-versioned cache'
            }
            $loadedModules = @($modules | Sort-Object | ForEach-Object {
                [pscustomobject]@{Path=$_;SHA256=(Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash}
            })
            if (-not $process.CloseMainWindow() -or -not $process.WaitForExit(10000)) { throw 'The ordinary application did not close gracefully' }
            $process.WaitForExit()
            $process.Refresh()
            if ($process.ExitCode -ne 0) { throw "Ordinary application exited $($process.ExitCode)" }
            if (Select-String -LiteralPath $stderr -Pattern 'Exception|Error|FATAL' -Quiet) { throw 'Ordinary application reported a runtime error' }
            $checks += [pscustomobject]@{
                Mode=$mode;Passed=$true;InitiallyEmpty=$true;OrdinaryEntryPoint=$true;DefaultSkikoCache=$true
                ResponsiveWindowMilliseconds=($timer.ElapsedMilliseconds - $readyAt);ExitCode=$process.ExitCode
                Profile=$profile;ICUPath=$icu[0].FullName;ICUBytes=$icu[0].Length;LoadedModules=$loadedModules
            }
            Write-Host "Ordinary isolated startup passed: $mode ($($modules.Count) modules; ICU $($icu[0].Length) bytes)"
        } finally {
            @($modules | Sort-Object) | Set-Content -LiteralPath (Join-Path $report "$mode-modules.txt") -Encoding utf8
            if ($process -and -not $process.HasExited) { $process.Kill(); $process.WaitForExit() }
            if ($process) { $process.Dispose() }
        }
    }
} catch {
    $failure = $_
} finally {
    foreach ($name in $savedEnvironment.Keys) {
        if ($null -eq $savedEnvironment[$name]) { Remove-Item -LiteralPath "Env:$name" -ErrorAction SilentlyContinue }
        else { [Environment]::SetEnvironmentVariable($name, $savedEnvironment[$name]) }
    }
    [pscustomobject]@{
        Passed=($null -eq $failure);Failure=if ($failure) { $failure.Exception.Message } else { $null }
        PackageSHA256=(Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash
        WindowsVersion=[Environment]::OSVersion.VersionString;PowerShellVersion=$PSVersionTable.PSVersion.ToString()
        JavaAvailableInIsolatedPath=$javaAvailable;Runtime=$runtime;Checks=$checks
    } | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $report 'isolation-result.json') -Encoding utf8
}
if ($failure) { throw $failure }

# Independently check real Compose frames, pickers, JNI, conversion and both renderers.
& (Join-Path $PSScriptRoot 'VerifyNativeImageWindows.ps1') -ArchivePath $archive -CheckRuntime
