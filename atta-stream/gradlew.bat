@echo off
setlocal
set "APP_HOME=%~dp0"
set "WRAPPER=%APP_HOME%gradle\wrapper\gradle-wrapper.jar"
if not exist "%WRAPPER%" (
  echo Downloading official Gradle wrapper 8.13...
  powershell -NoProfile -ExecutionPolicy Bypass -Command "Invoke-WebRequest -Uri 'https://raw.githubusercontent.com/gradle/gradle/v8.13.0/gradle/wrapper/gradle-wrapper.jar' -OutFile '%WRAPPER%'"
  if errorlevel 1 exit /b 1
)
if defined JAVA_HOME (
  set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
) else (
  set "JAVA_EXE=java.exe"
)
"%JAVA_EXE%" %JAVA_OPTS% %GRADLE_OPTS% -classpath "%WRAPPER%" org.gradle.wrapper.GradleWrapperMain %*
endlocal
