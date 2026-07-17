[CmdletBinding()]
param(
    [string]$Serial,
    [ValidateRange(15, 3600)]
    [int]$DurationSeconds = 180,
    [string]$OutputRoot = ".\validation\rootlesszachdsp",
    [string]$PackageName = "com.zfkirke0109.rootlesszachdsp.debug",
    [switch]$ClearLogcat
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

function Save-AdbText {
    param(
        [Parameter(Mandatory = $true)][string]$RelativePath,
        [Parameter(Mandatory = $true)][string[]]$Arguments
    )

    $destination = Join-Path $script:CaptureDirectory $RelativePath
    $parent = Split-Path -Parent $destination
    if (-not (Test-Path -LiteralPath $parent)) {
        New-Item -ItemType Directory -Path $parent -Force | Out-Null
    }
    try {
        Invoke-AdbText -Arguments $Arguments | Set-Content -LiteralPath $destination -Encoding UTF8
    }
    catch {
        "COMMAND FAILED: adb $($Arguments -join ' ')`r`n$($_.Exception.Message)" |
            Set-Content -LiteralPath $destination -Encoding UTF8
    }
}

if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
    throw "adb was not found in PATH. Install Android SDK Platform-Tools and reopen PowerShell."
}

$timestamp = Get-Date -Format "yyyyMMdd_HHmmss"
$CaptureDirectory = Join-Path $OutputRoot "capture_$timestamp"
New-Item -ItemType Directory -Path $CaptureDirectory -Force | Out-Null

$deviceState = Invoke-AdbText -Arguments @("get-state")
if ($deviceState.Trim() -ne "device") {
    throw "ADB target is not ready: $($deviceState.Trim())"
}

$identity = [ordered]@{
    capturedAtLocal = (Get-Date).ToString("o")
    serialRequested = $Serial
    packageName = $PackageName
    durationSeconds = $DurationSeconds
    adbVersion = (& adb version 2>&1 | Out-String).Trim()
    deviceSerial = (Invoke-AdbText -Arguments @("get-serialno")).Trim()
    manufacturer = (Invoke-AdbText -Arguments @("shell", "getprop", "ro.product.manufacturer")).Trim()
    model = (Invoke-AdbText -Arguments @("shell", "getprop", "ro.product.model")).Trim()
    androidRelease = (Invoke-AdbText -Arguments @("shell", "getprop", "ro.build.version.release")).Trim()
    sdk = (Invoke-AdbText -Arguments @("shell", "getprop", "ro.build.version.sdk")).Trim()
    securityPatch = (Invoke-AdbText -Arguments @("shell", "getprop", "ro.build.version.security_patch")).Trim()
}
$identity | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $CaptureDirectory "capture_identity.json") -Encoding UTF8

Save-AdbText "package\package.txt" @("shell", "dumpsys", "package", $PackageName)
Save-AdbText "package\resolve_activity.txt" @("shell", "cmd", "package", "resolve-activity", "--brief", $PackageName)
Save-AdbText "package\services.txt" @("shell", "dumpsys", "activity", "services", $PackageName)
Save-AdbText "package\processes.txt" @("shell", "dumpsys", "activity", "processes", $PackageName)
Save-AdbText "package\meminfo.txt" @("shell", "dumpsys", "meminfo", $PackageName)
Save-AdbText "audio\audio.txt" @("shell", "dumpsys", "audio")
Save-AdbText "audio\audio_flinger.txt" @("shell", "dumpsys", "media.audio_flinger")
Save-AdbText "audio\audio_policy.txt" @("shell", "dumpsys", "media.audio_policy")
Save-AdbText "audio\media_session.txt" @("shell", "dumpsys", "media_session")
Save-AdbText "audio\usb.txt" @("shell", "dumpsys", "usb")
Save-AdbText "system\battery.txt" @("shell", "dumpsys", "battery")
Save-AdbText "system\thermalservice.txt" @("shell", "dumpsys", "thermalservice")
Save-AdbText "system\deviceidle.txt" @("shell", "dumpsys", "deviceidle")
Save-AdbText "system\power.txt" @("shell", "dumpsys", "power")
Save-AdbText "system\selected_properties.txt" @(
    "shell", "sh", "-c",
    "getprop | grep -Ei 'ro.product|ro.build.version|audio|usb|bluetooth|dalvik.vm|debug.sf|vendor.audio'"
)

if ($ClearLogcat) {
    Invoke-AdbText -Arguments @("logcat", "-c") | Out-Null
}

$pidText = ""
try {
    $pidText = (Invoke-AdbText -Arguments @("shell", "pidof", "-s", $PackageName)).Trim()
}
catch {
    $pidText = ""
}

$adbPrefix = Get-AdbPrefix
$appLog = Join-Path $CaptureDirectory "logs\app_pid_logcat.txt"
$audioLog = Join-Path $CaptureDirectory "logs\audio_system_logcat.txt"
New-Item -ItemType Directory -Path (Split-Path -Parent $appLog) -Force | Out-Null

$processes = @()
if (-not [string]::IsNullOrWhiteSpace($pidText)) {
    $appArguments = @()
    $appArguments += $adbPrefix
    $appArguments += @("logcat", "-v", "threadtime", "--pid=$pidText")
    $processes += Start-Process -FilePath "adb" -ArgumentList $appArguments -NoNewWindow -PassThru `
        -RedirectStandardOutput $appLog -RedirectStandardError ($appLog + ".stderr")
}
else {
    "Package process was not running when capture started." | Set-Content -LiteralPath $appLog -Encoding UTF8
}

$audioArguments = @()
$audioArguments += $adbPrefix
$audioArguments += @(
    "logcat", "-v", "threadtime",
    "RootlessZachDSP:V", "RootlessAudio:V", "AudioFlinger:V", "AudioPolicyManager:V",
    "AudioTrack:V", "AudioRecord:V", "AAudio:V", "MediaCodec:W", "*:S"
)
$processes += Start-Process -FilePath "adb" -ArgumentList $audioArguments -NoNewWindow -PassThru `
    -RedirectStandardOutput $audioLog -RedirectStandardError ($audioLog + ".stderr")

Write-Host "Capturing app/audio logs for $DurationSeconds seconds. Keep music playing and exercise the route or setting being tested."
Start-Sleep -Seconds $DurationSeconds

foreach ($process in $processes) {
    if (-not $process.HasExited) {
        Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
    }
}

Save-AdbText "after\audio.txt" @("shell", "dumpsys", "audio")
Save-AdbText "after\audio_flinger.txt" @("shell", "dumpsys", "media.audio_flinger")
Save-AdbText "after\audio_policy.txt" @("shell", "dumpsys", "media.audio_policy")
Save-AdbText "after\services.txt" @("shell", "dumpsys", "activity", "services", $PackageName)
Save-AdbText "after\meminfo.txt" @("shell", "dumpsys", "meminfo", $PackageName)
Save-AdbText "after\thermalservice.txt" @("shell", "dumpsys", "thermalservice")

@"
RootlessZachDSP redacted ADB evidence bundle

This script collects package-specific state, audio policy/AudioFlinger summaries, USB route state,
thermal and memory summaries, and bounded logcat output. It does not request bugreport, account,
notification, contacts, media-library, or raw PCM data. Review files before sharing because OEM
service dumps may still contain device-specific identifiers.

Package: $PackageName
Capture duration: $DurationSeconds seconds
App PID at capture start: $pidText
"@ | Set-Content -LiteralPath (Join-Path $CaptureDirectory "PRIVACY_README.txt") -Encoding UTF8

$zipPath = "$CaptureDirectory.zip"
if (Test-Path -LiteralPath $zipPath) {
    Remove-Item -LiteralPath $zipPath -Force
}
Compress-Archive -Path (Join-Path $CaptureDirectory "*") -DestinationPath $zipPath -CompressionLevel Optimal

Write-Host "Evidence directory: $CaptureDirectory"
Write-Host "Evidence ZIP:       $zipPath"
