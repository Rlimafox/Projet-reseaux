@echo off
REM Script de compilation du projet
echo Compilation du projet...
cd /d "%~dp0"
javac -d bin src\*.java
if %ERRORLEVEL% EQU 0 (
    echo Compilation reussie!
) else (
    echo Erreur de compilation!
    exit /b 1
)
