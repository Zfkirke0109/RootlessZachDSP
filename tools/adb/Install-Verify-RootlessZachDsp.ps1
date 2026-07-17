[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$ApkPath,
    [string]$Serial,
    [string]$PackageName = "com.zfkirke0109.rootlesszachdsp.debug",
    [string]$OutputRoot = ".\validation\rootlesszachdsp-install",
    [switch]$RestartSamsungLauncher
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Get-AdbPrefix {
    if ([string]::IsNullOrWhiteSpace($Serial)) {
        return @()
    }
    return @("-s", $Serial)
}

function Invoke-AdbText {
    param([Parameter(Mandatory = $true)][string[]]$Arguments)

    $allArguments = @()
    $allArguments += Get-AdbPrefix
    $allArguments += $Arguments
    $output = & adb @allArguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "adb $($Arguments -join ' ') failed with exit code $LASTEXITCODE`n$output"
    }
    return ($output | Out-String)
}

function Find-BuildTool {
    param([Parameter(Mandatory = $true)][string]$Name)

    $command = Get-Command $Name -ErrorAction SilentlyContinue
    if ($command) {
        return $command.Source
    }
    $sdkRoot = $env:ANDROID_SDK_ROOT
    if ([string]::IsNullOrWhiteSpace($sdkRoot)) {
        $sdkRoot = $env:ANDROID_HOME
    }
    if ([string]::IsNullOrWhiteSpace($sdkRoot)) {
        return $null
    }
    $buildToolsRoot = Join-Path $sdkRoot "build-tools"
    if (-not (Test-Path -LiteralPath $buildToolsRoot)) {
        return $null
    }
    $candidate = Get-ChildItem -LiteralPath $buildToolsRoot -Directory |
        Sort-Object Name -Descending |
        ForEach-Object { Join-Path $_.FullName $Name } |
        Where-Object { Test-Path -LiteralPath $_ } |
        Select-Object -First 1
    return $candidate
}

if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
    throw "adb was not found in PATH. Install Android SDK Platform-Tools and reopen PowerShell."
}

$resolvedApk = (Resolve-Path -LiteralPath $ApkPath).Path
$timestamp = Get-Date -Format "yyyyMMdd_HHmmss"
$outputDirectory = Join-Path $OutputRoot "install_$timestamp"
New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null

$deviceState = (Invoke-AdbText -Arguments @("get-state")).Trim()
if ($deviceState -ne "device") {
    throw "ADB target is not ready: $deviceState"
}

$sha256 = (Get-FileHash -LiteralPath $resolvedApk -Algorithm SHA256).Hash.ToLowerInvariant()
"$sha256  $resolvedApk" | Set-Content -LiteralPath (Join-Path $outputDirectory "APK_SHA256.txt") -Encoding UTF8

$aapt = Find-BuildTool -Name "aapt.exe"
if (-not $aapt) {
    $aapt = Find-BuildTool -Name "aapt"
}
$apksigner = Find-BuildTool -Name "apksigner.bat"
if (-not $apksigner) {
    $apksigner = Find-BuildTool -Name "apksigner"
}

if ($aapt) {
    & $aapt dump badging $resolvedApk 2>&1 |
        Set-Content -LiteralPath (Join-Path $outputDirectory "apk_badging.txt") -Encoding UTF8
    $badging = Get-Content -LiteralPath (Join-Path $outputDirectory "apk_badging.txt") -Raw
    if ($badging -notmatch [regex]::Escape($PackageName)) {
        throw "APK package did not match expected package $PackageName"
    }
    if ($badging -notmatch "ic_rootless_zach_launcher") {
        throw "APK launcher metadata does not reference ic_rootless_zach_launcher"
    }
    if ($badging -match "application-icon-.*ic_dsp_launcher") {
        throw "APK still resolves to the upstream debug launcher icon"
    }
}
else {
    "aapt was not found; local APK icon metadata was not inspected." |
        Set-Content -LiteralPath (Join-Path $outputDirectory "apk_badging_unavailable.txt") -Encoding UTF8
}

if ($apksigner) {
    & $apksigner verify --verbose --print-certs $resolvedApk 2>&1 |
        Set-Content -LiteralPath (Join-Path $outputDirectory "apk_signer.txt") -Encoding UTF8
    if ($LASTEXITCODE -ne 0) {
        throw "apksigner verification failed"
    }
}
else {
    "apksigner was not found; signer verification was not repeated locally." |
        Set-Content -LiteralPath (Join-Path $outputDirectory "apk_signer_unavailable.txt") -Encoding UTF8
}

$installArguments = @()
$installArguments += Get-AdbPrefix
$installArguments += @("install", "-r", "-d", $resolvedApk)
$installOutput = & adb @installArguments 2>&1
$installOutput | Set-Content -LiteralPath (Join-Path $outputDirectory "adb_install.txt") -Encoding UTF8
if ($LASTEXITCODE -ne 0 -or ($installOutput | Out-String) -notmatch "Success") {
    throw "ADB update install failed. Review adb_install.txt. Android will reject signer mismatch."
}

Invoke-AdbText -Arguments @("shell", "dumpsys", "package", $PackageName) |
    Set-Content -LiteralPath (Join-Path $outputDirectory "installed_package.txt") -Encoding UTF8
$resolvedActivity = (Invoke-AdbText -Arguments @(
    "shell", "cmd", "package", "resolve-activity", "--brief", $PackageName
)).Trim()
$resolvedActivity | Set-Content -LiteralPath (Join-Path $outputDirectory "resolved_activity.txt") -Encoding UTF8

if ([string]::IsNullOrWhiteSpace($resolvedActivity) -or $resolvedActivity -match "No activity") {
    throw "The installed package has no resolvable launcher activity."
}

if ($RestartSamsungLauncher) {
    try {
        Invoke-AdbText -Arguments @("shell", "am", "force-stop", "com.sec.android.app.launcher") | Out-Null
        Start-Sleep -Seconds 2
    }
    catch {
        $_.Exception.Message | Set-Content -LiteralPath (Join-Path $outputDirectory "launcher_restart_error.txt") -Encoding UTF8
    }
}

Invoke-AdbText -Arguments @("shell", "am", "force-stop", $PackageName) | Out-Null
Invoke-AdbText -Arguments @("logcat", "-c") | Out-Null
Invoke-AdbText -Arguments @(
    "shell", "monkey", "-p", $PackageName, "-c", "android.intent.category.LAUNCHER", "1"
) | Set-Content -LiteralPath (Join-Path $outputDirectory "launch.txt") -Encoding UTF8
Start-Sleep -Seconds 8
Invoke-AdbText -Arguments @("logcat", "-d", "-v", "threadtime", "--pid=$(Invoke-AdbText -Arguments @('shell','pidof','-s',$PackageName)).Trim()") |
    Set-Content -LiteralPath (Join-Path $outputDirectory "cold_launch_logcat.txt") -Encoding UTF8

@"
Install verification completed.

APK: $resolvedApk
SHA-256: $sha256
Package: $PackageName
Resolved activity: $resolvedActivity

The script verified update installation and launcher metadata when aapt was available. It does not
clear One UI Home data. The optional -RestartSamsungLauncher switch only force-stops the launcher so
it can reload icon resources; it does not erase the home-screen layout.
"@ | Set-Content -LiteralPath (Join-Path $outputDirectory "RESULT.txt") -Encoding UTF8

$zipPath = "$outputDirectory.zip"
Compress-Archive -Path (Join-Path $outputDirectory "*") -DestinationPath $zipPath -CompressionLevel Optimal
Write-Host "Install verification passed."
Write-Host "Results: $outputDirectory"
Write-Host "ZIP:     $zipPath"
