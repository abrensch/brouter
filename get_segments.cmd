@echo off

:: 1. ..\Dockerfile
:: 2. segments4
:: 3. misc\segments4

set SEGMENTS=E10_N45.rd5 E10_N50.rd5 E15_N45.rd5 E15_N50.rd5 E20_N45.rd5 E20_N50.rd5

IF EXIST "..\Dockerfiles" (
    for /f "tokens=2 delims==" %%i in ('findstr /I "SEGMENTSPATH" ..\Dockerfile') do SET SEGMENTSPATH=%%i
    
    goto download
)

IF EXIST "segments4" (
    set SEGMENTSPATH=segments4
    goto download
)

IF EXIST "misc\segments4" (
    set SEGMENTSPATH=misc\segments4
    goto download
)

:download

(for %%s in (%SEGMENTS%) do (
   curl.exe --output %SEGMENTSPATH%/%%s --url brouter.de/brouter/segments4/%%s
))

pause