# RootlessZachDSP PowerShell ADB tools

These scripts support Windows PowerShell 5.1 and PowerShell 7. Run them from the repository root after `adb devices` shows the Galaxy S23 Ultra as `device`.

## Install and verify an APK

```powershell
Set-ExecutionPolicy -Scope Process Bypass
.\tools\adb\Install-Verify-RootlessZachDsp.ps1 `
  -ApkPath ".\RootlessZachDSP-S23Ultra-source-fidelity-debug.apk"
```

For wireless ADB with more than one target:

```powershell
.\tools\adb\Install-Verify-RootlessZachDsp.ps1 `
  -ApkPath ".\RootlessZachDSP-S23Ultra-source-fidelity-debug.apk" `
  -Serial "192.168.1.50:37001"
```

Add `-RestartSamsungLauncher` only when One UI Home keeps showing a cached prior icon. The switch force-stops the launcher so it reloads resources; it does not clear launcher data or erase the home-screen layout.

The script:

- verifies APK package and launcher metadata when Android build-tools are installed;
- verifies the APK signature when `apksigner` is available;
- performs `adb install -r` without uninstalling;
- resolves and launches the launcher activity;
- captures a short package-only cold-launch log;
- produces a reviewable ZIP under `validation\rootlesszachdsp-install`.

## Collect bounded audio evidence

Start music and then run:

```powershell
.\tools\adb\Collect-RootlessZachDspEvidence.ps1 -DurationSeconds 300
```

Wireless/multiple-device example:

```powershell
.\tools\adb\Collect-RootlessZachDspEvidence.ps1 `
  -Serial "192.168.1.50:37001" `
  -DurationSeconds 600 `
  -ClearLogcat
```

During the timed window, exercise only the route or setting being investigated, such as speaker to Bluetooth, screen lock, Atmos mode, or USB connection. The script captures target-package state, audio policy, AudioFlinger, USB, memory, thermal, and bounded logcat evidence before creating a ZIP.

It deliberately does not collect a full Android bugreport, accounts, notifications, contacts, media-library contents, or raw PCM. Review the ZIP before sharing because Samsung service dumps can still include device-specific identifiers.
