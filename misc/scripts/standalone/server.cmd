@echo off

REM BRouter standalone server
REM java -cp brouter.jar btools.brouter.RouteServer <segmentdir> <profile-map> <port>

set JAVA_OPTS=-Xmx128M -Xms128M -Xmn8M
set CLASSPATH=../brouter.jar

java %JAVA_OPTS% -cp %CLASSPATH% btools.server.RouteServer ..\segments2 ..\profiles2 17777
