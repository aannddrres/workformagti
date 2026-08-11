@echo off
set PATH=C:\PROGRA~1\nodejs;%PATH%
REM Hardcode the long path rather than trusting %~dp0: launch.json invokes
REM this script via its Windows 8.3 short path (MAGTIB~1), so %~dp0 would
REM resolve to that short-path form too. Vite's dev-server computes its
REM fs.allow serving allow-list from process.cwd() at startup -- a
REM short-path cwd there mismatches the long-path form the OS normalizes
REM real browser requests to, causing intermittent 403s on static assets
REM (e.g. public/i18n/*.json) served from outside the src/ tree.
cd /d "C:\Projects\Magti base\angular-frontend"
npm run start -- --port 4200
