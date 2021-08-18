@echo off

REM BRouter standalone server
REM java -cp brouter.jar btools.brouter.RouteServer <segmentdir> <profile-map> <customprofiledir> <port> <maxthreads>

pushd %~dp0

REM maxRunningTime is the request timeout in seconds, set to 0 to disable timeout
set JAVA_OPTS=-Xmx128M -Xms128M -Xmn8M -DmaxRunningTime=300

REM First search in locations matching the directory structure as found in the official BRouter zip archive
set CLASSPATH=../brouter.jar
set SEGMENTSPATH=..\segments4
set PROFILESPATH=..\profiles2
set CUSTOMPROFILESPATH=..\customprofiles

REM Otherwise try to locate files inside the source checkout
if not exist "%CLASSPATH%" (
    for /f "tokens=*" %%w in (
        'where /R ..\..\..\brouter-server\build\libs brouter-*-all.jar'
    ) do (
        set CLASSPATH=%%w
    )
)
if not exist "%SEGMENTSPATH%" (
    set SEGMENTSPATH=..\..\segments4
)
if not exist "%PROFILESPATH%" (
    set PROFILESPATH=..\..\profiles2
)
if not exist "%CUSTOMPROFILESPATH%" (
    set CUSTOMPROFILESPATH=..\..\customprofiles
)

java %JAVA_OPTS% -cp %CLASSPATH% btools.server.RouteServer "%SEGMENTSPATH%" "%PROFILESPATH%" "%CUSTOMPROFILESPATH%" 17777 1

popd
