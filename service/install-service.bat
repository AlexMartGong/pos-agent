@echo off
REM ============================================
REM Instalar POS Printer Agent como Servicio
REM Ejecutar como Administrador
REM ============================================

echo === Instalando POS Printer Agent como Servicio ===
echo.

REM Verificar permisos de administrador
net session >nul 2>&1
if errorlevel 1 (
    echo ERROR: Este script requiere permisos de Administrador
    echo Haz clic derecho y selecciona "Ejecutar como administrador"
    pause
    exit /b 1
)

REM Obtener directorio del script
set SCRIPT_DIR=%~dp0

REM Verificar archivos necesarios
if not exist "%SCRIPT_DIR%pos-printer-agent.exe" (
    echo ERROR: No se encuentra pos-printer-agent.exe
    echo Asegurate de que WinSW este renombrado a pos-printer-agent.exe
    pause
    exit /b 1
)

if not exist "%SCRIPT_DIR%pos-agent.jar" (
    echo ERROR: No se encuentra pos-agent.jar
    pause
    exit /b 1
)

if not exist "%SCRIPT_DIR%config.properties" (
    echo ADVERTENCIA: No se encuentra config.properties
    echo El servicio usara la configuracion por defecto
    echo.
)

REM Instalar servicio
echo Instalando servicio...
"%SCRIPT_DIR%pos-printer-agent.exe" install
if errorlevel 1 (
    echo ERROR: Fallo la instalacion del servicio
    pause
    exit /b 1
)

REM Iniciar servicio
echo Iniciando servicio...
"%SCRIPT_DIR%pos-printer-agent.exe" start
if errorlevel 1 (
    echo ADVERTENCIA: El servicio se instalo pero no pudo iniciar
    echo Revisa la configuracion y los logs
)

echo.
echo === Servicio instalado exitosamente ===
echo.
echo El servicio "POS Printer Agent" iniciara automaticamente con Windows.
echo.
echo Comandos utiles:
echo   - Ver estado:    pos-printer-agent.exe status
echo   - Detener:       pos-printer-agent.exe stop
echo   - Iniciar:       pos-printer-agent.exe start
echo   - Reiniciar:     pos-printer-agent.exe restart
echo   - Desinstalar:   uninstall-service.bat
echo.
echo Logs en: %SCRIPT_DIR%pos-printer-agent.out.log
echo.
pause
