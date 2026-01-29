@echo off
echo Building APK in WSL...
wsl bash /mnt/c/Users/Kenny/repos/Fermata/build-wsl.sh

if %ERRORLEVEL% NEQ 0 (
    echo Build failed!
    exit /b %ERRORLEVEL%
)

echo.
echo Installing APK to device...
for %%f in (fermata\build\outputs\apk\auto\debug\*.apk) do (
    echo Installing %%f
    adb install -r "%%f"
    if %ERRORLEVEL% NEQ 0 (
        echo Installation failed!
        exit /b %ERRORLEVEL%
    )
)

echo.
echo Done! APK built and installed successfully.
pause
