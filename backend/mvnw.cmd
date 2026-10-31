@echo off
setlocal
set "MAVEN_VERSION=3.9.16"
set "MAVEN_HOME=%USERPROFILE%\.m2\wrapper\dists\apache-maven-%MAVEN_VERSION%"
if not exist "%MAVEN_HOME%\bin\mvn.cmd" (
  set "MAVEN_ARCHIVE=%TEMP%\apache-maven-%MAVEN_VERSION%-bin.zip"
  echo Downloading Apache Maven %MAVEN_VERSION% for the first run...
  powershell -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; $url='https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/%MAVEN_VERSION%/apache-maven-%MAVEN_VERSION%-bin.zip'; Invoke-WebRequest -UseBasicParsing -Uri $url -OutFile $env:TEMP\apache-maven-%MAVEN_VERSION%-bin.zip; Expand-Archive -Force $env:TEMP\apache-maven-%MAVEN_VERSION%-bin.zip $env:TEMP; New-Item -ItemType Directory -Force -Path $env:USERPROFILE\.m2\wrapper\dists | Out-Null; Move-Item -Force $env:TEMP\apache-maven-%MAVEN_VERSION% $env:USERPROFILE\.m2\wrapper\dists\apache-maven-%MAVEN_VERSION%"
  if errorlevel 1 exit /b 1
)
call "%MAVEN_HOME%\bin\mvn.cmd" %*
exit /b %ERRORLEVEL%
