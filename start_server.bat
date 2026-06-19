@echo off
chcp 65001 >nul
echo ირთვება მაგთის პორტალის ლოკალური სერვერი...
echo.

if not exist "venv\Scripts\activate.bat" (
    echo [1/4] ვქმნი ვირტუალურ გარემოს...
    python -m venv venv
)
call venv\Scripts\activate.bat

echo [2/4] ვიწერ საჭირო ბიბლიოთეკებს...
pip install -r requirements.txt

if not exist "magti_portal.db" (
    echo.
    echo [3/4] ვასუფთავებ ძველ ფაილებს და ვქმნი ახალ ბაზას...
    if exist "magti_portal.db-wal" del /f /q "magti_portal.db-wal"
    if exist "magti_portal.db-shm" del /f /q "magti_portal.db-shm"
    python seed.py
)

echo.
echo [4/4] სერვერი ირთვება...
uvicorn main:app --reload
pause