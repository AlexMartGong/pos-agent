@echo off
REM ============================================
REM Desinstalar POS Printer Agent Servicio
REM Ejecutar como Administrador
REM ============================================

echo === Desinstalando POS Printer Agent Servicio ===
echo.

REM Verificar permisos de administrador
net session >nul 2>&1
if errorlevel 1 (
    echo ERROR: Este script requiere permisos de Administrador
    echo Haz clic derecho y selecciona "Ejecutar como administrador"
    pause
    exit /b 1
)

set SCRIPT_DIR=%~dp0

REM Detener servicio si esta corriendo
echo Deteniendo servicio...
"%SCRIPT_DIR%pos-printer-agent.exe" stop 2>nul

REM Desinstalar servicio
echo Desinstalando servicio...
"%SCRIPT_DIR%pos-printer-agent.exe" uninstall
if errorlevel 1 (
    echo ERROR: Fallo la desinstalacion
    pause
    exit /b 1
)

echo.
echo === Servicio desinstalado exitosamente ===
echo.
pause