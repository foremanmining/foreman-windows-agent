@echo off
setlocal enabledelayedexpansion
setlocal

set "AGENT_HOME=%~dp0.."
set "JAVA_HOME=%AGENT_HOME%\bin\support\win64\jdk-11.0.8+10-jre"
set "JAVA=%JAVA_HOME%\bin\java.exe"

for /f "delims=" %%x in ('dir /od /b "%AGENT_HOME%\lib\windows-agent*.jar"') do set "JAR=%%x"

echo Note: to run in the background, install and start as a service
echo;
echo To do that:
echo - run 'service-start.bat' to install and auto-start
echo - run 'service-stop.bat' to stop
echo;
echo Starting agent...(leave this window open)
"%JAVA%" -jar "%AGENT_HOME%\lib\%JAR%" %*

endlocal