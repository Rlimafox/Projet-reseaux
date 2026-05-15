@echo off
REM Script pour lancer le Receiver
REM Usage: run_receiver.bat <port>
REM Exemple: run_receiver.bat 5000

if "%1"=="" (
    echo Usage: run_receiver.bat ^<port^>
    echo Exemple: run_receiver.bat 5000
    exit /b 1
)

cd /d "%~dp0"

REM Compilation si le dossier bin n'existe pas
if not exist "bin" (
    echo Compilation en cours...
    javac -d bin src\*.java
    if %ERRORLEVEL% NEQ 0 (
        echo Erreur de compilation!
        exit /b 1
    )
)

echo Lancement du Receiver sur le port %1...
java -cp bin Receiver %1
