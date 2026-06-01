@echo off
REM ============================================
REM Crear paquete de servicio para Windows
REM Incluye WinSW para instalacion como servicio
REM ============================================

echo === POS Printer Agent - Build Service Package ===
echo.

REM Usar Maven wrapper si existe, sino mvn del sistema
set MVN_CMD=
if exist "mvnw.cmd" (
    set MVN_CMD=mvnw.cmd
) else (
    where mvn >nul 2>&1
    if errorlevel 1 (
        echo ERROR: Maven no esta instalado y no hay mvnw.cmd
        exit /b 1
    )
    set MVN_CMD=mvn
)

echo [1/4] Compilando proyecto...
call %MVN_CMD% clean package -DskipTests -q
if errorlevel 1 (
    echo ERROR: Fallo la compilacion
    exit /b 1
)

echo [2/4] Creando directorio de distribucion...
set DIST_DIR=target\pos-printer-agent-service
if exist "%DIST_DIR%" rmdir /s /q "%DIST_DIR%"
mkdir "%DIST_DIR%"

echo [3/4] Descargando WinSW...
set WINSW_VERSION=2.12.0
set WINSW_URL=https://github.com/winsw/winsw/releases/download/v%WINSW_VERSION%/WinSW-x64.exe
set WINSW_FILE=%DIST_DIR%\pos-printer-agent.exe

REM Intentar descargar con curl (Windows 10+)
curl -L -o "%WINSW_FILE%" "%WINSW_URL%" 2>nul
if errorlevel 1 (
    REM Intentar con PowerShell
    powershell -Command "Invoke-WebRequest -Uri '%WINSW_URL%' -OutFile '%WINSW_FILE%'" 2>nul
    if errorlevel 1 (
        echo ERROR: No se pudo descargar WinSW
        echo Descarga manualmente desde: %WINSW_URL%
        echo Y renombralo a pos-printer-agent.exe
        exit /b 1
    )
)

echo [4/4] Copiando archivos...
copy "target\pos-agent.jar" "%DIST_DIR%\" >nul
copy "service\pos-printer-agent.xml" "%DIST_DIR%\" >nul
copy "service\install-service.bat" "%DIST_DIR%\" >nul
copy "service\uninstall-service.bat" "%DIST_DIR%\" >nul
copy "config.properties" "%DIST_DIR%\" >nul 2>nul

REM Wrapper de arranque + auto-actualizacion (swap del jar antes de lanzar la JVM)
echo @echo off> "%DIST_DIR%\run-agent.bat"
echo cd /d "%%~dp0">> "%DIST_DIR%\run-agent.bat"
echo if exist pos-agent-next.jar (>> "%DIST_DIR%\run-agent.bat"
echo     echo [VentaCore] Aplicando actualizacion de agente...>> "%DIST_DIR%\run-agent.bat"
echo     move /y pos-agent-next.jar pos-agent.jar>> "%DIST_DIR%\run-agent.bat"
echo )>> "%DIST_DIR%\run-agent.bat"
echo echo [VentaCore] Iniciando agente...>> "%DIST_DIR%\run-agent.bat"
echo java -Xmx256m -jar pos-agent.jar %%*>> "%DIST_DIR%\run-agent.bat"

REM Crear README
echo POS Printer Agent - Instalacion como Servicio > "%DIST_DIR%\LEEME.txt"
echo ============================================== >> "%DIST_DIR%\LEEME.txt"
echo. >> "%DIST_DIR%\LEEME.txt"
echo 1. Edita config.properties con tu configuracion >> "%DIST_DIR%\LEEME.txt"
echo 2. Ejecuta install-service.bat como Administrador >> "%DIST_DIR%\LEEME.txt"
echo 3. El servicio iniciara automaticamente con Windows >> "%DIST_DIR%\LEEME.txt"
echo. >> "%DIST_DIR%\LEEME.txt"
echo Para desinstalar: uninstall-service.bat >> "%DIST_DIR%\LEEME.txt"

echo.
echo === Paquete creado exitosamente ===
echo.
echo Ubicacion: %DIST_DIR%\
echo.
echo Contenido:
dir /b "%DIST_DIR%"
echo.
echo Instrucciones:
echo 1. Copia la carpeta "%DIST_DIR%" a la PC destino
echo 2. Edita config.properties
echo 3. Ejecuta install-service.bat como Administrador
echo.
pause
