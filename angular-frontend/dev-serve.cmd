@echo off
set PATH=C:\PROGRA~1\nodejs;%PATH%
cd /d "%~dp0"
npm run start -- --port 4200
