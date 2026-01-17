#!/bin/bash
# ============================================
# Crear paquete de servicio para Windows
# Ejecutar desde Linux, transferir a Windows
# ============================================

set -e

echo "=== POS Printer Agent - Build Service Package ==="
echo

# Usar Maven wrapper si existe, sino mvn del sistema
if [ -f "./mvnw" ]; then
    MVN="./mvnw"
elif command -v mvn &> /dev/null; then
    MVN="mvn"
else
    echo "ERROR: Maven no esta instalado y no hay mvnw"
    exit 1
fi

echo "[1/4] Compilando proyecto..."
$MVN clean package -DskipTests -q

echo "[2/4] Creando directorio de distribucion..."
DIST_DIR="target/pos-printer-agent-service"
rm -rf "$DIST_DIR"
mkdir -p "$DIST_DIR"

echo "[3/4] Descargando WinSW..."
WINSW_VERSION="2.12.0"
WINSW_URL="https://github.com/winsw/winsw/releases/download/v${WINSW_VERSION}/WinSW-x64.exe"
WINSW_FILE="$DIST_DIR/pos-printer-agent.exe"

if command -v curl &> /dev/null; then
    curl -L -o "$WINSW_FILE" "$WINSW_URL"
elif command -v wget &> /dev/null; then
    wget -O "$WINSW_FILE" "$WINSW_URL"
else
    echo "ERROR: Necesitas curl o wget para descargar WinSW"
    exit 1
fi

echo "[4/4] Copiando archivos..."
cp "target/pos-printer-agent.jar" "$DIST_DIR/"
cp "service/pos-printer-agent.xml" "$DIST_DIR/"
cp "service/install-service.bat" "$DIST_DIR/"
cp "service/uninstall-service.bat" "$DIST_DIR/"
cp "config.properties" "$DIST_DIR/" 2>/dev/null || true

# Crear README
cat > "$DIST_DIR/LEEME.txt" << 'EOF'
POS Printer Agent - Instalacion como Servicio
==============================================

1. Edita config.properties con tu configuracion
2. Ejecuta install-service.bat como Administrador
3. El servicio iniciara automaticamente con Windows

Para desinstalar: uninstall-service.bat
EOF

# Crear ZIP
echo
echo "Creando archivo ZIP..."
cd target
zip -r pos-printer-agent-service.zip pos-printer-agent-service/
cd ..

echo
echo "=== Paquete creado exitosamente ==="
echo
echo "Archivos:"
echo "  - $DIST_DIR/ (carpeta)"
echo "  - target/pos-printer-agent-service.zip"
echo
echo "Instrucciones:"
echo "1. Transfiere el ZIP a la PC Windows"
echo "2. Extrae el contenido"
echo "3. Edita config.properties"
echo "4. Ejecuta install-service.bat como Administrador"
echo
