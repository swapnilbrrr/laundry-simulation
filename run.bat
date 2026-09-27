@echo off
rem Compile and run the Smart Laundry simulation (CT074-3-2)
cd /d "%~dp0"
if not exist out mkdir out
javac -d out src\laundry\*.java
if errorlevel 1 exit /b 1
java -cp out laundry.Main %*
