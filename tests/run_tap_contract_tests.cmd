@echo off
setlocal
call "C:\Program Files\Microsoft Visual Studio\2022\Community\VC\Auxiliary\Build\vcvars64.bat" >nul
if errorlevel 1 exit /b 1
cd /d "%~dp0.."
if not exist "app\build\tap-contract-tests" mkdir "app\build\tap-contract-tests"
cl /nologo /std:c11 /W3 /I native\vendor\rknn tests\rknn_tap_contract_test.c /Fo:app\build\tap-contract-tests\contract.obj /Fe:app\build\tap-contract-tests\contract.exe
if errorlevel 1 exit /b 1
app\build\tap-contract-tests\contract.exe
if errorlevel 1 exit /b 1
cl /nologo /std:c11 /W3 /I native\vendor\rknn tests\rknn_tap_iteration_test.c /Fo:app\build\tap-contract-tests\iteration.obj /Fe:app\build\tap-contract-tests\iteration.exe
if errorlevel 1 exit /b 1
app\build\tap-contract-tests\iteration.exe
if errorlevel 1 exit /b 1
cl /nologo /std:c11 /W3 /I native\vendor\rknn tests\rknn_stem_contract_test.c /Fo:app\build\tap-contract-tests\stem.obj /Fe:app\build\tap-contract-tests\stem.exe
if errorlevel 1 exit /b 1
app\build\tap-contract-tests\stem.exe
if errorlevel 1 exit /b 1
cl /nologo /std:c11 /W3 /DMIRROR_TAP_PROFILE=1 /I native\vendor\rknn tests\rknn_tap_iteration_test.c /Fo:app\build\tap-contract-tests\stem-iteration.obj /Fe:app\build\tap-contract-tests\stem-iteration.exe
if errorlevel 1 exit /b 1
app\build\tap-contract-tests\stem-iteration.exe
if errorlevel 1 exit /b 1
cl /nologo /std:c11 /W3 /I native\vendor\rknn tests\rknn_ln_contract_test.c /Fo:app\build\tap-contract-tests\ln.obj /Fe:app\build\tap-contract-tests\ln.exe
if errorlevel 1 exit /b 1
app\build\tap-contract-tests\ln.exe
if errorlevel 1 exit /b 1
cl /nologo /std:c11 /W3 /DMIRROR_TAP_PROFILE=2 /I native\vendor\rknn tests\rknn_tap_iteration_test.c /Fo:app\build\tap-contract-tests\ln-iteration.obj /Fe:app\build\tap-contract-tests\ln-iteration.exe
if errorlevel 1 exit /b 1
app\build\tap-contract-tests\ln-iteration.exe
if errorlevel 1 exit /b 1
cl /nologo /std:c11 /W3 /I native\vendor\rknn tests\rknn_scale_contract_test.c /Fo:app\build\tap-contract-tests\scale.obj /Fe:app\build\tap-contract-tests\scale.exe
if errorlevel 1 exit /b 1
app\build\tap-contract-tests\scale.exe
if errorlevel 1 exit /b 1
cl /nologo /std:c11 /W3 /DMIRROR_TAP_PROFILE=3 /I native\vendor\rknn tests\rknn_tap_iteration_test.c /Fo:app\build\tap-contract-tests\scale-iteration.obj /Fe:app\build\tap-contract-tests\scale-iteration.exe
if errorlevel 1 exit /b 1
app\build\tap-contract-tests\scale-iteration.exe
exit /b %errorlevel%
