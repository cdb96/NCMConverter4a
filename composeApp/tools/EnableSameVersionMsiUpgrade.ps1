param(
    [Parameter(Mandatory = $true)][string]$MsiPath,
    [Parameter(Mandatory = $true)][string]$PackageName,
    [Parameter(Mandatory = $true)][string]$PackageVersion,
    [Parameter(Mandatory = $true)][string]$ReplaceThroughVersion,
    [Parameter(Mandatory = $true)][string]$UpgradeCode,
    [string[]]$LegacyUpgradeCodes = @(),
    [Parameter(Mandatory = $true)][string]$RestoreInstallDirDll
)

$ErrorActionPreference = 'Stop'
if ([Version]$ReplaceThroughVersion -lt [Version]$PackageVersion) {
    throw 'ReplaceThroughVersion must not be older than PackageVersion'
}
$expectedUpgradeCode = '{' + ([Guid]$UpgradeCode).ToString().ToUpperInvariant() + '}'
$installer = New-Object -ComObject WindowsInstaller.Installer
$database = $installer.OpenDatabase((Resolve-Path -LiteralPath $MsiPath).Path, 1)

$properties = @{}
$view = $database.OpenView('SELECT * FROM `Property`')
$view.Execute()
while ($record = $view.Fetch()) {
    $properties[$record.StringData(1)] = $record.StringData(2)
}
$view.Close()

if ($properties.ProductName -ne $PackageName -or
    $properties.ProductVersion -ne $PackageVersion -or
    $properties.UpgradeCode -ne $expectedUpgradeCode -or
    -not $properties.ContainsKey('ProductCode')) {
    throw "Unexpected MSI identity: $MsiPath"
}

$replaceRowCount = 0
$blockRowCount = 0
$view = $database.OpenView('SELECT * FROM `Upgrade`')
$view.Execute()
while ($record = $view.Fetch()) {
    if ($record.StringData(1) -ne $expectedUpgradeCode) {
        continue
    }
    if ($record.StringData(3) -eq $PackageVersion -and
        $record.StringData(7) -eq 'JP_UPGRADABLE_FOUND') {
        $replaceRowCount++
        $attributes = [int]$record.IntegerData(5)
        if (($attributes -band 2) -ne 0) {
            throw "The matching Upgrade row only detects products: $MsiPath"
        }
        # Include the current version and the earlier 4.0.1 build in replacement.
        # 512 includes VersionMax; REPLACE handles nullable Upgrade table keys.
        $record.StringData(3) = $ReplaceThroughVersion
        $record.IntegerData(5) = $attributes -bor 512
        $view.Modify(4, $record)
    } elseif ($record.StringData(2) -eq $PackageVersion -and
              $record.StringData(7) -eq 'JP_DOWNGRADABLE_FOUND') {
        $blockRowCount++
        # Versions through 4.0.1 can be replaced; only newer ones are blocked.
        $record.StringData(2) = $ReplaceThroughVersion
        $record.IntegerData(5) = ([int]$record.IntegerData(5)) -band (-bnot 1)
        $view.Modify(4, $record)
    }
}
$view.Close()

if ($replaceRowCount -ne 1 -or $blockRowCount -ne 1) {
    throw "Expected one upgrade row and one downgrade guard in $MsiPath"
}

# Older builds generated their UpgradeCode from jpackage's localized default
# manufacturer. Keep that known identity in the major-upgrade migration range.
foreach ($legacyCode in $LegacyUpgradeCodes) {
    $legacyUpgradeCode = '{' + ([Guid]$legacyCode).ToString().ToUpperInvariant() + '}'
    $legacyRows = @(
        @('JP_UPGRADABLE_FOUND', 'NCM_LEGACY_UPGRADABLE_FOUND'),
        @('JP_DOWNGRADABLE_FOUND', 'NCM_LEGACY_DOWNGRADABLE_FOUND')
    )
    foreach ($rowNames in $legacyRows) {
        $select = $database.OpenView('SELECT * FROM `Upgrade` WHERE `UpgradeCode` = ? AND `ActionProperty` = ?')
        $queryRecord = $installer.CreateRecord(2)
        $queryRecord.StringData(1) = $expectedUpgradeCode
        $queryRecord.StringData(2) = $rowNames[0]
        $select.Execute($queryRecord)
        $sourceRecord = $select.Fetch()
        $select.Close()
        if (-not $sourceRecord) {
            throw "Could not find the $($rowNames[0]) row to migrate $legacyUpgradeCode"
        }

        $insertRecord = $installer.CreateRecord(7)
        $insertRecord.StringData(1) = $legacyUpgradeCode
        $insertRecord.StringData(2) = $sourceRecord.StringData(2)
        $insertRecord.StringData(3) = $sourceRecord.StringData(3)
        $insertRecord.StringData(4) = $sourceRecord.StringData(4)
        $insertRecord.IntegerData(5) = $sourceRecord.IntegerData(5)
        $insertRecord.StringData(6) = $sourceRecord.StringData(6)
        $insertRecord.StringData(7) = $rowNames[1]
        $insert = $database.OpenView('INSERT INTO `Upgrade` (`UpgradeCode`, `VersionMin`, `VersionMax`, `Language`, `Attributes`, `Remove`, `ActionProperty`) VALUES (?, ?, ?, ?, ?, ?, ?)')
        $insert.Execute($insertRecord)
        $insert.Close()
    }
}

if ($LegacyUpgradeCodes.Count -gt 0) {
    $view = $database.OpenView('SELECT `Property`, `Value` FROM `Property` WHERE `Property` = ?')
    $queryRecord = $installer.CreateRecord(1)
    $queryRecord.StringData(1) = 'SecureCustomProperties'
    $view.Execute($queryRecord)
    $record = $view.Fetch()
    $view.Close()
    if (-not $record) {
        throw "SecureCustomProperties was not found in $MsiPath"
    }
    $secureProperties = @($record.StringData(2) -split ';' | Where-Object { $_ })
    foreach ($name in @('NCM_LEGACY_UPGRADABLE_FOUND', 'NCM_LEGACY_DOWNGRADABLE_FOUND')) {
        if ($name -notin $secureProperties) {
            $secureProperties += $name
        }
    }
    $record.StringData(2) = $secureProperties -join ';'
    $view = $database.OpenView('UPDATE `Property` SET `Value` = ? WHERE `Property` = ?')
    $updateRecord = $installer.CreateRecord(2)
    $updateRecord.StringData(1) = $record.StringData(2)
    $updateRecord.StringData(2) = 'SecureCustomProperties'
    $view.Execute($updateRecord)
    $view.Close()
}

# FindRelatedProducts lists the old ProductCode. Run before CostInitialize in
# both sequences so the directory chooser and unattended installs use its path.
$restoreDllPath = (Resolve-Path -LiteralPath $RestoreInstallDirDll).Path
$record = $installer.CreateRecord(2)
$record.StringData(1) = 'NcmRestoreInstallDirDll'
$record.SetStream(2, $restoreDllPath)
$view = $database.OpenView('INSERT INTO `Binary` (`Name`, `Data`) VALUES (?, ?)')
$view.Execute($record)
$view.Close()

$record = $installer.CreateRecord(4)
$record.StringData(1) = 'NcmRestoreInstallDir'
$record.IntegerData(2) = 1  # DLL stored in the Binary table.
$record.StringData(3) = 'NcmRestoreInstallDirDll'
$record.StringData(4) = 'RestoreInstallDir'
$view = $database.OpenView('INSERT INTO `CustomAction` (`Action`, `Type`, `Source`, `Target`) VALUES (?, ?, ?, ?)')
$view.Execute($record)
$view.Close()

foreach ($table in @('InstallUISequence', 'InstallExecuteSequence')) {
    $record = $installer.CreateRecord(3)
    $record.StringData(1) = 'NcmRestoreInstallDir'
    $record.StringData(2) = 'NOT Installed AND (JP_UPGRADABLE_FOUND OR NCM_LEGACY_UPGRADABLE_FOUND)'
    $record.IntegerData(3) = 55  # After FindRelatedProducts/AppSearch, before costing.
    $view = $database.OpenView("INSERT INTO ``$table`` (``Action``, ``Condition``, ``Sequence``) VALUES (?, ?, ?)")
    $view.Execute($record)
    $view.Close()
}

# jpackage derives ProductCode from vendor/name/version. Give every new build
# a distinct product identity so Windows runs the major-upgrade path.
$productCode = '{' + [Guid]::NewGuid().ToString().ToUpperInvariant() + '}'
$view = $database.OpenView('SELECT * FROM `Property`')
$view.Execute()
while ($record = $view.Fetch()) {
    if ($record.StringData(1) -eq 'ProductCode') {
        $record.StringData(2) = $productCode
        $view.Modify(2, $record)
        break
    }
}
$view.Close()

# The package itself also needs a fresh identity after changing its database.
$packageCode = '{' + [Guid]::NewGuid().ToString().ToUpperInvariant() + '}'
$summary = $database.SummaryInformation(1)
$summary.Property(9) = $packageCode
$summary.Persist()
$database.Commit()

Write-Output "Enabled same-version MSI upgrade: $PackageVersion, ProductCode=$productCode, PackageCode=$packageCode"
