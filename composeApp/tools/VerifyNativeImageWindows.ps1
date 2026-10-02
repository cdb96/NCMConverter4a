param([Parameter(Mandatory = $true)][string]$ArchivePath)

$ErrorActionPreference = 'Stop'
$archive = (Resolve-Path -LiteralPath $ArchivePath).Path
# Chinese characters plus a space exercise the process's UTF-8 code page.
$folder = '' + [char]0x4FBF + [char]0x643A + ' ' + [char]0x8FD0 + [char]0x884C
$validationRoot = Join-Path $PWD 'build/native-image/portable-smoke'
$destination = Join-Path $validationRoot $folder
if (Test-Path -LiteralPath $destination) { throw 'Validation extraction directory already exists' }
New-Item -ItemType Directory -Path $destination | Out-Null
Add-Type -AssemblyName System.IO.Compression.FileSystem
[System.IO.Compression.ZipFile]::ExtractToDirectory($archive, $destination)
$runtime = Join-Path $destination 'NCMConverter4a'
$appData = Join-Path $validationRoot 'appdata'
$temp = Join-Path $validationRoot 'temp'
New-Item -ItemType Directory -Force $temp,(Join-Path $appData 'NCMConverter4a') | Out-Null
$originalEnvironment = @{}
foreach ($name in @('APPDATA', 'JAVA_HOME', 'PATH', 'TEMP', 'TMP', 'SKIKO_RENDER_API')) {
    $originalEnvironment[$name] = [Environment]::GetEnvironmentVariable($name)
}
try {
    $env:JAVA_HOME = ''
    $env:PATH = "$env:SystemRoot\System32;$env:SystemRoot"
    $env:APPDATA = $appData
    $env:TEMP = $temp
    $env:TMP = $temp
    Remove-Item Env:SKIKO_RENDER_API -ErrorAction SilentlyContinue
    foreach ($test in @('native-smoke','file-picker-smoke','file-picker-app-smoke','startup-true','startup-false','conversion-true','conversion-false')) {
        $gpu = if ($test.EndsWith('false')) { 'false' } else { 'true' }
        "gpuRendering=$gpu" | Set-Content -LiteralPath (Join-Path $appData 'NCMConverter4a/rendering.properties') -Encoding ascii
        $flag = if ($test.StartsWith('startup-')) { '--startup-smoke' } elseif ($test.StartsWith('conversion-')) { '--pgo-train' } else { "--$test" }
        $stdout = Join-Path $runtime "$test.stdout.log"
        $stderr = Join-Path $runtime "$test.stderr.log"
        $process = Start-Process -FilePath (Join-Path $runtime 'NCMConverter4a.exe') -WorkingDirectory $runtime -ArgumentList $flag -PassThru -WindowStyle Hidden -RedirectStandardOutput $stdout -RedirectStandardError $stderr
        $handle = $process.Handle
        if (-not $process.WaitForExit(45000)) { $process.Kill(); throw "Packaged $test timed out" }
        $process.Refresh()
        Get-Content -LiteralPath $stdout
        Get-Content -LiteralPath $stderr
        if ($process.ExitCode -ne 0) { throw "Packaged $test failed: $($process.ExitCode)" }
        # Native AWT can print JNI errors without a nonzero process exit code.
        if (Select-String -LiteralPath $stderr -Pattern 'Exception in thread|NoSuchMethodError|UnsatisfiedLinkError|MissingReflectionRegistrationError|MissingJNIRegistrationError|Fatal error|Uncaught smoke failure' -Quiet) {
            throw "Packaged $test reported a runtime error; see $stderr"
        }
        if ($test -match '^(startup|conversion)-') {
            $expected = if ($gpu -eq 'true') { 'DIRECT3D' } else { 'SOFTWARE_FAST' }
            if (-not (Select-String -LiteralPath $stdout -Pattern "Skiko renderer: $expected" -SimpleMatch -Quiet)) { throw "Renderer mismatch: $test" }
        }
        if ($test.StartsWith('conversion-') -and -not (Select-String -LiteralPath $stdout -Pattern 'PGO conversion training passed' -SimpleMatch -Quiet)) { throw 'Conversion workload did not pass' }
        Write-Host "Packaged check passed: $test"
    }
    Get-FileHash -LiteralPath $archive -Algorithm SHA256
    Get-Item -LiteralPath $archive | Select-Object FullName,Length
} finally {
    foreach ($name in $originalEnvironment.Keys) {
        [Environment]::SetEnvironmentVariable($name, $originalEnvironment[$name])
    }
}
