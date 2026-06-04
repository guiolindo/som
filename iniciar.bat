@echo off
title PC Audio Stream
color 0B
cd /d "%~dp0"

echo.
echo  ==========================================
echo          PC Audio Stream - Setup
echo  ==========================================
echo.

python --version >nul 2>&1
if errorlevel 1 (
    echo  [ERRO] Python nao encontrado.
    echo  Instale em: https://www.python.org/downloads/
    echo.
    pause
    exit /b 1
)

echo  [1/2] Instalando dependencias...
echo.
pip install "fastapi>=0.110" "uvicorn[standard]>=0.29" "soundcard>=0.4.2" "sounddevice>=0.4.6" "numpy>=1.24" "aiortc>=1.13" "av>=11" --quiet --upgrade
if errorlevel 1 (
    echo.
    echo  [ERRO] Falha ao instalar dependencias.
    pause
    exit /b 1
)

echo.
echo  [2/2] Iniciando servidor...
echo.
echo  ------------------------------------------
echo   Pressione Ctrl+C para encerrar
echo  ------------------------------------------
echo.

python servidor.py

echo.
echo  Servidor encerrado.
pause
