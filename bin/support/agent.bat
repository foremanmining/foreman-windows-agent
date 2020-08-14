@echo off
setlocal enabledelayedexpansion
setlocal

set "AGENT_HOME=%~dp0..\.."
set "JAVA_HOME=%AGENT_HOME%\bin\support\win64\jdk-11.0.8+10-jre"
set "JAVA=%JAVA_HOME%\bin\java.exe"

rem # Set JVM parameters
set "JVM_PARAMS=-DLOG_LOCATION=%AGENT_HOME%\logs"

for /f "delims=" %%x in ('dir /od /b "%AGENT_HOME%\lib\windows-agent*.jar"') do set "JAR=%%x"

for /F "tokens=1* delims==" %%A in ('type "%AGENT_HOME%\conf\foreman.txt"') do (
    if "%%A" == "clientId" set "CLIENT_ID=%%B"
    if "%%A" == "apiKey" set "API_KEY=%%B"
)
If NOT "%CLIENT_ID%"=="%CLIENT_ID:replace_me=%" (
    echo Missing 'clientId' in foreman.txt
    pause
    exit /b 1
)
If NOT "%API_KEY%"=="%API_KEY:replace_me=%" (
    echo Missing 'apiKey' in foreman.txt
    pause
    exit /b 1
)

echo Note: to run in the background, install and start as a service
echo;
echo To do that:
echo - run 'service-start.bat' to install and auto-start
echo - run 'service-stop.bat' to stop
echo;
echo Starting agent...(leave this window open)
"%JAVA%" "%JVM_PARAMS%" -jar "%AGENT_HOME%\lib\%JAR%" "--logging.config=%AGENT_HOME%\etc\logback.xml" "--agent.home=%AGENT_HOME%" --client.id=%CLIENT_ID% --client.apiKey=%API_KEY%

endlocal