package com.pasadita.pos.scale;

import com.fazecast.jSerialComm.SerialPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Controlador para báscula Torrey PCR Series
 * Sin dependencias de Spring Boot - Solo Java puro
 */
public class TorreyScaleController {

    private static final Logger logger = LoggerFactory.getLogger(TorreyScaleController.class);

    // Configuración del puerto serial
    private static final int BAUD_RATE = 9600;
    private static final int DATA_BITS = 8;
    private static final int STOP_BITS = SerialPort.ONE_STOP_BIT;
    private static final int PARITY = SerialPort.NO_PARITY;

    // Timeouts
    private static final int READ_TIMEOUT = 2000; // 2 segundos
    private static final int WRITE_TIMEOUT = 1000; // 1 segundo

    // Comandos Torrey PCR
    private static final byte[] COMMAND_READ_WEIGHT = {0x57}; // 'W' - Solicitar peso

    private SerialPort serialPort;
    private String portName;
    private boolean isConnected;

    public TorreyScaleController(String portName) {
        this.portName = portName;
        this.isConnected = false;
    }

    /**
     * Conecta con el puerto serial de la báscula
     */
    public boolean connect() {
        try {
            logger.info("Intentando conectar con báscula Torrey en puerto: {}", portName);

            // Buscar el puerto
            serialPort = SerialPort.getCommPort(portName);

            // Configurar parámetros del puerto
            serialPort.setComPortParameters(BAUD_RATE, DATA_BITS, STOP_BITS, PARITY);
            serialPort.setComPortTimeouts(
                    SerialPort.TIMEOUT_READ_BLOCKING | SerialPort.TIMEOUT_WRITE_BLOCKING,
                    READ_TIMEOUT,
                    WRITE_TIMEOUT
            );

            // Abrir puerto
            if (serialPort.openPort()) {
                isConnected = true;
                logger.info("Conectado exitosamente a la báscula en {}", portName);

                // Esperar un momento para estabilizar la conexión
                TimeUnit.MILLISECONDS.sleep(500);

                return true;
            } else {
                logger.error("No se pudo abrir el puerto {}", portName);
                return false;
            }

        } catch (Exception e) {
            logger.error("Error al conectar con la báscula: {}", e.getMessage(), e);
            isConnected = false;
            return false;
        }
    }

    /**
     * Desconecta del puerto serial
     */
    public void disconnect() {
        if (serialPort != null && serialPort.isOpen()) {
            serialPort.closePort();
            isConnected = false;
            logger.info("Desconectado de la báscula");
        }
    }

    /**
     * Lee el peso actual de la báscula
     */
    public WeightReading readWeight() {
        if (!isConnected || serialPort == null || !serialPort.isOpen()) {
            logger.error("No hay conexión con la báscula");
            return WeightReading.error("No conectado");
        }

        try {
            // Limpiar buffers antes de leer
            flushBuffers();

            // Enviar comando de lectura
            int bytesWritten = serialPort.writeBytes(COMMAND_READ_WEIGHT, COMMAND_READ_WEIGHT.length);

            if (bytesWritten < 0) {
                logger.error("Error al enviar comando a la báscula");
                return WeightReading.error("Error de escritura");
            }

            // Esperar respuesta
            TimeUnit.MILLISECONDS.sleep(100);

            // Leer respuesta
            byte[] buffer = new byte[32];
            int bytesRead = serialPort.readBytes(buffer, buffer.length);

            if (bytesRead > 0) {
                String response = new String(buffer, 0, bytesRead, StandardCharsets.US_ASCII).trim();
                logger.debug("Respuesta de báscula: '{}' ({} bytes)", response, bytesRead);

                // Parsear la respuesta
                return parseWeightResponse(response);
            } else {
                logger.warn("No se recibió respuesta de la báscula");
                return WeightReading.error("Sin respuesta");
            }

        } catch (Exception e) {
            logger.error("Error al leer peso: {}", e.getMessage(), e);
            return WeightReading.error("Error: " + e.getMessage());
        }
    }

    /**
     * Parsea la respuesta de la báscula Torrey PCR
     */
    private WeightReading parseWeightResponse(String response) {
        try {
            // Remover espacios y separadores
            String cleaned = response.replaceAll("[^0-9.kg-]", "");

            // Buscar el peso numérico
            String weightStr = cleaned.replaceAll("[kg-]", "").trim();

            if (weightStr.isEmpty()) {
                return WeightReading.zero();
            }

            double weight = Double.parseDouble(weightStr);

            // Determinar si es estable
            boolean stable = response.contains("ST") || !response.contains("US");

            // Determinar unidad
            String unit = response.toLowerCase().contains("kg") ? "kg" : "g";

            // Verificar si es peso negativo
            boolean negative = response.contains("-");
            if (negative) {
                weight = -weight;
            }

            logger.info("Peso leído: {} {} ({})", weight, unit, stable ? "estable" : "inestable");

            return new WeightReading(weight, unit, stable, true, null);

        } catch (NumberFormatException e) {
            logger.error("Error al parsear peso de respuesta: '{}'", response);
            return WeightReading.error("Formato inválido");
        }
    }

    /**
     * Limpia los buffers de entrada y salida
     */
    private void flushBuffers() {
        try {
            if (serialPort.bytesAvailable() > 0) {
                byte[] trash = new byte[serialPort.bytesAvailable()];
                serialPort.readBytes(trash, trash.length);
            }
        } catch (Exception e) {
            logger.warn("Error al limpiar buffers: {}", e.getMessage());
        }
    }

    /**
     * Verifica si hay conexión activa
     */
    public boolean isConnected() {
        return isConnected && serialPort != null && serialPort.isOpen();
    }

    /**
     * Obtiene el nombre del puerto
     */
    public String getPortName() {
        return portName;
    }

    /**
     * Lista todos los puertos seriales disponibles
     */
    public static String[] getAvailablePorts() {
        SerialPort[] ports = SerialPort.getCommPorts();
        String[] portNames = new String[ports.length];

        for (int i = 0; i < ports.length; i++) {
            portNames[i] = ports[i].getSystemPortName();
        }

        return portNames;
    }

    /**
     * Clase interna para representar una lectura de peso
     */
    public static class WeightReading {
        private final double weight;
        private final String unit;
        private final boolean stable;
        private final boolean success;
        private final String errorMessage;

        public WeightReading(double weight, String unit, boolean stable, boolean success, String errorMessage) {
            this.weight = weight;
            this.unit = unit;
            this.stable = stable;
            this.success = success;
            this.errorMessage = errorMessage;
        }

        public static WeightReading zero() {
            return new WeightReading(0.0, "kg", true, true, null);
        }

        public static WeightReading error(String message) {
            return new WeightReading(0.0, "kg", false, false, message);
        }

        // Getters
        public double getWeight() {
            return weight;
        }

        public String getUnit() {
            return unit;
        }

        public boolean isStable() {
            return stable;
        }

        public boolean isSuccess() {
            return success;
        }

        public String getErrorMessage() {
            return errorMessage;
        }

        @Override
        public String toString() {
            if (!success) {
                return String.format("Error: %s", errorMessage);
            }
            return String.format("%.3f %s (%s)", weight, unit, stable ? "estable" : "inestable");
        }
    }
}