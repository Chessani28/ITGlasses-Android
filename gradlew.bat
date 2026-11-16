@echo off
setlocal
set GRADLE_CMD=C:\Users\VR\.gradle\wrapper\dists\gradle-8.14.1-bin\baw1sv0jfoi8rxs14qo3h49cs\gradle-8.14.1\bin\gradle.bat
if not exist "%GRADLE_CMD%" (
    echo Gradle not found at %GRADLE_CMD%
    exit /b 1
)
"%GRADLE_CMD%" %*