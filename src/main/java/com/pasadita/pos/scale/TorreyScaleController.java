package com.pasadita.pos.scale;

import com.fazecast.jSerialComm.SerialPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TorreyScaleController {

    private static final Logger logger = LoggerFactory.getLogger(TorreyScaleController.class);

    private static final int BAUD_RATE = 9600;
    private static final int DATA_BITS = 8;
    private static final int STOP_BITS = SerialPort.ONE_STOP_BIT;
    private static final int PARITY = SerialPort.NO_PARITY;

    private static final int READ_TIMEOUT = 2000;
    private static final int WRITE_TIMEOUT = 1000;

    private static final byte[] COMMAND_READ_WEIGHT = {0x57}; // 'W' - Solicitar peso

    private static final int POLLING_INTERVAL = 300;

    private static final int STABILITY_WINDOW = 5;

    private static final Pattern WEIGHT_PATTERN = Pattern.compile("-?\\d+\\.?\\d*");

    private SerialPort serialPort;
    private final String portName;
    private volatile boolean isConnected;

    private volatile WeightReading lastReading = WeightReading.zero();
    private volatile long lastReadTime = 0;

    private Thread pollingThread;
    private volatile boolean pollingActive = false;

    private final AtomicInteger consecutiveErrors = new AtomicInteger(0);
    private static final int MAX_CONSECUTIVE_ERRORS = 5;

    private final BigDecimal[] stabilityBuffer = new BigDecimal[STABILITY_WINDOW];
    private int stabilityIndex = 0;
    private int stabilityCount = 0;

    public TorreyScaleController(String portName) {
        this.portName = portName;
        this.isConnected = false;
    }

    public boolean connect() {
        try {
            logger.info("Intentando conectar con báscula Torrey en puerto: {}", portName);

            cleanupConnection();

            serialPort = SerialPort.getCommPort(portName);

            serialPort.setComPortParameters(BAUD_RATE, DATA_BITS, STOP_BITS, PARITY);
            serialPort.setComPortTimeouts(
                    SerialPort.TIMEOUT_READ_BLOCKING | SerialPort.TIMEOUT_WRITE_BLOCKING,
                    READ_TIMEOUT,
                    WRITE_TIMEOUT
            );

            if (serialPort.openPort()) {
                isConnected = true;
                consecutiveErrors.set(0);
                lastReadTime = 0;
                resetStabilityBuffer();
                logger.info("Conectado exitosamente a la báscula en {}", portName);

                TimeUnit.MILLISECONDS.sleep(500);

                startPolling();

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

    public void disconnect() {
        stopPolling();

        if (serialPort != null) {
            try {
                if (serialPort.isOpen()) {
                    serialPort.closePort();
                }
            } catch (Exception e) {
                logger.warn("Error al cerrar puerto: {}", e.getMessage());
            }
            serialPort = null;
        }

        isConnected = false;
        lastReading = WeightReading.zero();
        lastReadTime = 0;
        consecutiveErrors.set(0);
        resetStabilityBuffer();
        logger.info("Desconectado de la báscula");
    }

    private void cleanupConnection() {
        stopPolling();

        if (serialPort != null) {
            try {
                if (serialPort.isOpen()) {
                    serialPort.closePort();
                }
            } catch (Exception e) {
                logger.debug("Error al limpiar conexión anterior: {}", e.getMessage());
            }
            serialPort = null;
        }

        isConnected = false;
        lastReading = WeightReading.zero();
        lastReadTime = 0;
        consecutiveErrors.set(0);
        resetStabilityBuffer();
    }

    private void resetStabilityBuffer() {
        Arrays.fill(stabilityBuffer, null);
        stabilityIndex = 0;
        stabilityCount = 0;
    }

    private boolean updateStabilityAndCheck(BigDecimal weight) {
        stabilityBuffer[stabilityIndex] = weight;
        stabilityIndex = (stabilityIndex + 1) % STABILITY_WINDOW;
        if (stabilityCount < STABILITY_WINDOW) {
            stabilityCount++;
        }

        if (stabilityCount < STABILITY_WINDOW) {
            return false;
        }

        for (int i = 0; i < STABILITY_WINDOW; i++) {
            if (stabilityBuffer[i] == null || stabilityBuffer[i].compareTo(weight) != 0) {
                return false;
            }
        }
        return true;
    }

    private void startPolling() {
        if (pollingActive) {
            return;
        }

        pollingActive = true;
        pollingThread = new Thread(() -> {
            logger.info("Iniciando polling de báscula cada {}ms", POLLING_INTERVAL);
            while (pollingActive && isConnected) {
                try {
                    WeightReading reading = readWeightInternal();

                    if (reading.success()) {
                        consecutiveErrors.set(0);
                        lastReading = reading;
                        lastReadTime = System.currentTimeMillis();
                    } else {
                        int errors = consecutiveErrors.incrementAndGet();
                        logger.warn("Error en lectura #{}: {}", errors, reading.errorMessage());

                        if (errors >= MAX_CONSECUTIVE_ERRORS) {
                            logger.error("Detectada desconexión física de la báscula después de {} errores consecutivos", errors);
                            isConnected = false;
                            break;
                        }
                    }

                    TimeUnit.MILLISECONDS.sleep(POLLING_INTERVAL);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    int errors = consecutiveErrors.incrementAndGet();
                    logger.warn("Excepción en polling #{}: {}", errors, e.getMessage());

                    if (errors >= MAX_CONSECUTIVE_ERRORS) {
                        logger.error("Detectada desconexión física de la báscula después de {} excepciones consecutivas", errors);
                        isConnected = false;
                        break;
                    }
                }
            }
            logger.info("Polling de báscula detenido");
        }, "scale-polling");
        pollingThread.setDaemon(true);
        pollingThread.start();
    }

    private void stopPolling() {
        pollingActive = false;
        if (pollingThread != null) {
            pollingThread.interrupt();
            try {
                pollingThread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            pollingThread = null;
        }
    }

    public WeightReading readWeight() {
        if (!isConnected || serialPort == null || !serialPort.isOpen()) {
            return WeightReading.error("No conectado");
        }

        if (lastReadTime == 0) {
            return WeightReading.zero();
        }

        if (System.currentTimeMillis() - lastReadTime > 3000) {
            logger.warn("Datos de báscula desactualizados ({}ms)", System.currentTimeMillis() - lastReadTime);
            return new WeightReading(lastReading.weight(), lastReading.unit(), false, true, null);
        }

        return lastReading;
    }

    private WeightReading readWeightInternal() {
        if (!isConnected || serialPort == null || !serialPort.isOpen()) {
            return WeightReading.error("No conectado");
        }

        try {
            flushBuffers();

            int bytesWritten = serialPort.writeBytes(COMMAND_READ_WEIGHT, COMMAND_READ_WEIGHT.length);

            if (bytesWritten < 0) {
                logger.error("Error al enviar comando a la báscula");
                return WeightReading.error("Error de escritura");
            }

            TimeUnit.MILLISECONDS.sleep(100);

            byte[] buffer = new byte[32];
            int bytesRead = serialPort.readBytes(buffer, buffer.length);

            if (bytesRead > 0) {
                String response = new String(buffer, 0, bytesRead, StandardCharsets.US_ASCII).trim();
                logger.debug("Respuesta de báscula: '{}' ({} bytes)", response, bytesRead);

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

    private WeightReading parseWeightResponse(String response) {
        try {
            StringBuilder cleaned = new StringBuilder();
            for (char c : response.toCharArray()) {
                if (c >= 0x20 && c <= 0x7E) {
                    cleaned.append(c);
                }
            }
            String cleanResponse = cleaned.toString().trim();

            if (cleanResponse.isEmpty()) {
                return WeightReading.zero();
            }

            boolean negative = cleanResponse.contains("-");
            String unit = cleanResponse.toLowerCase().contains("kg") ? "kg" : "g";
            boolean frameStable = cleanResponse.contains("ST") || !cleanResponse.contains("US");
            String withoutUnit = cleanResponse.replaceAll("(?i)(kg|g\\b)", "")
                    .replaceAll("[^0-9.\\-]", " ")
                    .trim();

            Matcher matcher = WEIGHT_PATTERN.matcher(withoutUnit);
            if (!matcher.find()) {
                return WeightReading.zero();
            }

            String weightStr = matcher.group();

            BigDecimal weight = new BigDecimal(weightStr).setScale(3, RoundingMode.HALF_UP);

            if (negative && weight.compareTo(BigDecimal.ZERO) > 0) {
                weight = weight.negate();
            }

            boolean softwareStable = updateStabilityAndCheck(weight);

            boolean stable = frameStable && softwareStable;

            logger.info("Peso leído: {} {} ({})", weight.toPlainString(), unit, stable ? "estable" : "inestable");

            return new WeightReading(weight, unit, stable, true, null);

        } catch (NumberFormatException e) {
            logger.error("Error al parsear peso de respuesta: '{}'", response);
            return WeightReading.error("Formato inválido");
        } catch (ArithmeticException e) {
            logger.error("Error aritmético al procesar peso: '{}'", response);
            return WeightReading.error("Error de precisión");
        }
    }

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

    public boolean isConnected() {
        return isConnected && serialPort != null && serialPort.isOpen();
    }

    public String getPortName() {
        return portName;
    }

    public static String[] getAvailablePorts() {
        SerialPort[] ports = SerialPort.getCommPorts();
        String[] portNames = new String[ports.length];

        for (int i = 0; i < ports.length; i++) {
            portNames[i] = ports[i].getSystemPortName();
        }

        return portNames;
    }

    public record WeightReading(BigDecimal weight, String unit, boolean stable, boolean success, String errorMessage) {

        public static WeightReading zero() {
            return new WeightReading(BigDecimal.ZERO.setScale(3, RoundingMode.HALF_UP), "kg", true, true, null);
        }

        public static WeightReading error(String message) {
            return new WeightReading(BigDecimal.ZERO.setScale(3, RoundingMode.HALF_UP), "kg", false, false, message);
        }

        @Override
        public String toString() {
            if (!success) {
                return String.format("Error: %s", errorMessage);
            }
            return String.format("%s %s (%s)", weight.toPlainString(), unit, stable ? "estable" : "inestable");
        }
    }
}
