@echo off
REM Script pour lancer le Sender
REM Usage: run_sender.bat <ip> <port> <fichier>
REM Exemple: run_sender.bat 127.0.0.1 5000 test.txt

if "%3"=="" (
    echo Usage: run_sender.bat ^<ip^> ^<port^> ^<fichier^>
    echo Exemple: run_sender.bat 127.0.0.1 5000 test.txt
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

echo Lancement du Sender vers %1:%2 avec le fichier %3...
java -cp bin Sender %1 %2 %3
