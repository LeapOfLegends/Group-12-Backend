#!/bin/bash
# ETL Pipeline Setup Script
# Run this script to set up the ETL pipeline environment

set -e

echo "================================"
echo "ETL Pipeline Setup Script"
echo "================================"
echo ""

# Check Python version
echo "[1/5] Checking Python version..."
python_version=$(python3 --version 2>&1 | awk '{print $2}')
echo "  Python version: $python_version"

# Create virtual environment
echo "[2/5] Creating virtual environment..."
if [ ! -d ".venv" ]; then
    python3 -m venv .venv
    echo "  Virtual environment created"
else
    echo "  Virtual environment already exists"
fi

# Activate virtual environment
echo "[3/5] Activating virtual environment..."
source .venv/bin/activate

# Install dependencies
echo "[4/5] Installing dependencies..."
pip install --upgrade pip
pip install -r requirements.txt
echo "  Dependencies installed"

# Create .env file if it doesn't exist
echo "[5/5] Setting up configuration..."
if [ ! -f ".env" ]; then
    cp .env.example .env
    echo "  Created .env file from template"
    echo "  ⚠️  Please edit .env with your database credentials"
else
    echo "  .env file already exists"
fi

echo ""
echo "================================"
echo "Setup Complete!"
echo "================================"
echo ""
echo "Next steps:"
echo "1. Edit .env with your database credentials"
echo "2. Ensure both OLTP and DW databases are running"
echo "3. Run: python main.py"
echo ""
echo "For more details, see README.md"
