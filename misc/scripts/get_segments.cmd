@echo off

set SEGMENTS=E10_N45.rd5 E15_N45.rd5 E15_N50.rd5 E20_N45.rd5 E20_N50.rd5

IF EXIST "..\segments4" (
    set SEGMENTSPATH=..\segments4
    goto download
)

IF EXIST "..\..\segments4" (
    set SEGMENTSPATH=..\..\segments4
    goto download
)

echo Segments path not found, aborting...
pause
exit /b 1

:download

(for %%s in (%SEGMENTS%) do (
   curl.exe --output %SEGMENTSPATH%/%%s --url brouter.de/brouter/segments4/%%s
))

echo.
echo.
echo Done!
pause
exit /b 0