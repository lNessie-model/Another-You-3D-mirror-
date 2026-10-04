@echo off
setlocal
call "C:\Program Files\Microsoft Visual Studio\2022\Community\VC\Auxiliary\Build\vcvars64.bat" >nul
if errorlevel 1 exit /b 1
cd /d "%~dp0.."
if not exist "app\build\blendshape-contract-tests" mkdir "app\build\blendshape-contract-tests"
cl /nologo /std:c11 /W3 /I native\vendor\rknn tests\rknn_blendshape_contract_test.c /Fo:app\build\blendshape-contract-tests\contract.obj /Fe:app\build\blendshape-contract-tests\contract.exe
if errorlevel 1 exit /b 1
app\build\blendshape-contract-tests\contract.exe
exit /b %errorlevel%
