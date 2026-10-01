@echo off
REM ETL Pipeline Setup Script for Windows
REM Run this script to set up the ETL pipeline environment

echo.
echo ================================
echo ETL Pipeline Setup Script
echo ================================
echo.

REM Check Python version
echo [1/5] Checking Python version...
python --version
if errorlevel 1 (
    echo ERROR: Python is not installed or not in PATH
    exit /b 1
)

REM Create virtual environment
echo [2/5] Creating virtual environment...
if not exist ".venv" (
    python -m venv .venv
    echo Virtual environment created
) else (
    echo Virtual environment already exists
)

REM Activate virtual environment
echo [3/5] Activating virtual environment...
call .venv\Scripts\activate.bat

REM Install dependencies
echo [4/5] Installing dependencies...
python -m pip install --upgrade pip
pip install -r requirements.txt
echo Dependencies installed

REM Create .env file if it doesn't exist
echo [5/5] Setting up configuration...
if not exist ".env" (
    copy .env.example .env
    echo Created .env file from template
    echo WARNING: Please edit .env with your database credentials
) else (
    echo .env file already exists
)

echo.
echo ================================
echo Setup Complete!
echo ================================
echo.
echo Next steps:
echo 1. Edit .env with your database credentials
echo 2. Ensure both OLTP and DW databases are running
echo 3. Run: python main.py
echo.
echo For more details, see README.md
echo.
pause
