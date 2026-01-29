#!/usr/bin/env pwsh
Write-Host "Building APK in WSL..." -ForegroundColor Cyan

wsl bash /mnt/c/Users/Kenny/repos/Fermata/build-wsl.sh

if ($LASTEXITCODE -ne 0) {
    Write-Host "Build failed!" -ForegroundColor Red
    exit $LASTEXITCODE
}

Write-Host ""
Write-Host "Installing APK to device..." -ForegroundColor Cyan

$apk = Get-ChildItem -Path "fermata\build\outputs\apk\auto\debug\*.apk" | Select-Object -First 1

if ($apk) {
    Write-Host "Installing $($apk.Name)" -ForegroundColor Yellow
    & adb install -r $apk.FullName

    if ($LASTEXITCODE -ne 0) {
        Write-Host "Installation failed!" -ForegroundColor Red
        exit $LASTEXITCODE
    }
} else {
    Write-Host "No APK found!" -ForegroundColor Red
    exit 1
}

Write-Host ""
Write-Host "Done! APK built and installed successfully." -ForegroundColor Green
Read-Host "Press Enter to continue"
