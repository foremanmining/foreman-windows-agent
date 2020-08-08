@echo off
CLS

:init
setlocal DisableDelayedExpansion
set "batchPath=%~0"
for %%k in (%0) do set batchName=%%~nk
set "vbsGetPrivileges=%temp%\OEgetPriv_%batchName%.vbs"
setlocal EnableDelayedExpansion

:checkPrivileges
NET FILE 1>NUL 2>NUL
if '%errorlevel%' == '0' ( goto gotPrivileges ) else ( goto getPrivileges )

:getPrivileges
if '%1'=='ELEV' (echo ELEV & shift /1 & goto gotPrivileges)

echo Set UAC = CreateObject^("Shell.Application"^) > "%vbsGetPrivileges%"
echo args = "ELEV " >> "%vbsGetPrivileges%"
echo For Each strArg in WScript.Arguments >> "%vbsGetPrivileges%"
echo args = args ^& strArg ^& " "  >> "%vbsGetPrivileges%"
echo Next >> "%vbsGetPrivileges%"
echo UAC.ShellExecute "!batchPath!", args, "", "runas", 1 >> "%vbsGetPrivileges%"
"%SystemRoot%\System32\WScript.exe" "%vbsGetPrivileges%" %*
exit /B

:gotPrivileges
setlocal & pushd .
cd /d %~dp0
if '%1'=='ELEV' (del "%vbsGetPrivileges%" 1>nul 2>nul  &  shift /1)

::::::::::::::::::::::::::::
::START
::::::::::::::::::::::::::::

set "AGENT_HOME=%~dp0.."
set "NSSM=%AGENT_HOME%\bin\support\win64\nssm.exe"

echo.
echo **************************************
echo Installing Agent
echo **************************************
echo.

for /F "tokens=1* delims==" %%A in ('type "%AGENT_HOME%\conf\foreman.txt"') do (
    if "%%A" == "clientId" set "CLIENT_ID=%%B"
    if "%%A" == "apiKey" set "API_KEY=%%B"
)

If NOT "%CLIENT_ID%"=="%CLIENT_ID:replace_me=%" (
    echo **************************************
    echo **************************************
    echo **************************************
    echo.
    echo Missing 'clientId' in foreman.txt
    echo.
    echo **************************************
    echo **************************************
    echo **************************************
    echo.
    goto done
)
If NOT "%API_KEY%"=="%API_KEY:replace_me=%" (
    echo **************************************
    echo **************************************
    echo **************************************
    echo.
    echo Missing 'apiKey' in foreman.txt
    echo.
    echo **************************************
    echo **************************************
    echo **************************************
    echo.
    goto done
)

echo Installing and starting service...
"%NSSM%" install Foreman "%AGENT_HOME%\bin\agent.bat" "\"--agent.home=%AGENT_HOME%\" --client.id=%CLIENT_ID% --client.apiKey=%API_KEY%"
"%NSSM%" set Foreman Description "The Foreman agent"
"%NSSM%" set Foreman Start SERVICE_AUTO_START
"%NSSM%" start Foreman

:done
pause