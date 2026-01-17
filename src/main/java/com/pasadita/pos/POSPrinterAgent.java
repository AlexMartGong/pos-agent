package com.pasadita.pos;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.pasadita.pos.dto.TicketDTO;
import com.pasadita.pos.scale.ScaleRestServer;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Agente de impresión POS que conecta al backend via WebSocket
 * y envía los tickets recibidos a la impresora térmica ESC/POS.
 * <p>
 * Configuración via variables de entorno:
 * - SERVER_URL: URL del servidor WebSocket (default: ws://localhost:8080/ws/printer)
 * - STATION_ID: ID de esta estación POS (default: POS1)
 * - PRINTER_PATH: Ruta del dispositivo de impresora (default: /dev/usb/lp0)
 * - BUSINESS_NAME: Nombre del negocio (default: LA PASADITA)
 * - BUSINESS_ADDRESS: Dirección del negocio
 * - BUSINESS_PHONE: Teléfono del negocio
 */
public class POSPrinterAgent extends WebSocketClient {

    // Configuración por defecto
    private static final String DEFAULT_SERVER_URL = "ws://localhost:8080/ws/printer";
    private static final String DEFAULT_STATION_ID = "POS1";
    private static final String DEFAULT_PRINTER_PATH = "/dev/usb/lp0";
    private static final String DEFAULT_SCALE_PORT = "/dev/ttyACM0";
    private static final boolean DEFAULT_SCALE_ENABLED = true;
    private static final boolean DEFAULT_SCALE_AUTO_CONNECT = true;
    private static final int RECONNECT_DELAY_SECONDS = 5;

    // Formato para logging
    private static final DateTimeFormatter LOG_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final String stationId;
    private final ESCPOSPrinter printer;
    private final ObjectMapper objectMapper;
    private final ScheduledExecutorService scheduler;
    private volatile boolean running = true;

    /**
     * Constructor principal que extiende WebSocketClient.
     *
     * @param serverUri URI completa del servidor WebSocket (incluyendo stationId)
     * @param stationId ID de esta estación POS
     * @param printer   Instancia del printer ESC/POS
     */
    public POSPrinterAgent(URI serverUri, String stationId, ESCPOSPrinter printer) {
        super(serverUri);
        this.stationId = stationId;
        this.printer = printer;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.scheduler = Executors.newSingleThreadScheduledExecutor();
    }

    @Override
    public void onOpen(ServerHandshake handshake) {
        log("INFO", "Conexión establecida con el servidor");
        log("INFO", "Esperando tickets para imprimir...");

        // Enviar confirmación de conexión
        try {
            String connectionMessage = String.format(
                    "{\"type\":\"CONNECTED\",\"stationId\":\"%s\",\"timestamp\":\"%s\"}",
                    stationId,
                    LocalDateTime.now().format(LOG_FORMAT)
            );
            send(connectionMessage);
            log("INFO", "Mensaje de conexión enviado al servidor");
        } catch (Exception e) {
            log("ERROR", "No se pudo enviar mensaje de conexión: " + e.getMessage());
        }
    }

    @Override
    public void onMessage(String message) {
        log("INFO", "Mensaje recibido del servidor (" + message.length() + " bytes)");

        try {
            // Deserializer JSON TicketDTO usando Jackson
            TicketDTO ticket = objectMapper.readValue(message, TicketDTO.class);
            log("INFO", "Ticket #" + ticket.getId() + " parseado - Cliente: " + ticket.getCustomerName());

            // Intentar imprimir el ticket
            printTicket(ticket);

        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            log("ERROR", "Error parseando JSON: " + e.getMessage());
            log("DEBUG", "Mensaje recibido: " + message.substring(0, Math.min(200, message.length())));
        } catch (Exception e) {
            log("ERROR", "Error procesando mensaje: " + e.getMessage());
            e.fillInStackTrace();
        }
    }

    /**
     * Imprime el ticket y envía confirmación al servidor.
     *
     * @param ticket TicketDTO an imprimir
     */
    private void printTicket(TicketDTO ticket) {
        boolean success = false;
        String errorMessage = null;

        try {
            if (printer.isAvailable()) {
                log("INFO", "Imprimiendo ticket #" + ticket.getId() + "...");
                printer.print(ticket);
                success = true;
                log("INFO", "Ticket #" + ticket.getId() + " impreso correctamente");
            } else {
                errorMessage = "Impresora no disponible o desconectada";
                log("ERROR", errorMessage);
            }
        } catch (IOException e) {
            errorMessage = "Error de I/O al imprimir: " + e.getMessage();
            log("ERROR", errorMessage);
        } catch (ESCPOSPrinter.PrinterException e) {
            errorMessage = "Error de impresora: " + e.getMessage();
            log("ERROR", errorMessage);
        } catch (Exception e) {
            errorMessage = "Error inesperado al imprimir: " + e.getMessage();
            log("ERROR", errorMessage);
            e.fillInStackTrace();
        }

        // Enviar confirmación de vuelta al servidor
        sendPrintConfirmation(ticket.getId(), success, errorMessage);
    }

    /**
     * Envía confirmación de impresión al servidor.
     *
     * @param ticketId ID del ticket
     * @param success  Si la impresión fue exitosa
     * @param error    Mensaje de error (null si exitoso)
     */
    private void sendPrintConfirmation(Long ticketId, boolean success, String error) {
        try {
            StringBuilder json = new StringBuilder();
            json.append("{");
            json.append("\"type\":\"PRINT_RESULT\",");
            json.append("\"ticketId\":").append(ticketId).append(",");
            json.append("\"success\":").append(success).append(",");
            json.append("\"stationId\":\"").append(stationId).append("\",");
            json.append("\"timestamp\":\"").append(LocalDateTime.now().format(LOG_FORMAT)).append("\"");
            if (error != null) {
                // Escapar caracteres especiales en el mensaje de error
                String escapedError = error.replace("\\", "\\\\").replace("\"", "\\\"");
                json.append(",\"error\":\"").append(escapedError).append("\"");
            }
            json.append("}");

            send(json.toString());
            log("INFO", "Confirmación enviada - Ticket #" + ticketId + ": " + (success ? "OK" : "FALLO"));
        } catch (Exception e) {
            log("ERROR", "No se pudo enviar confirmación: " + e.getMessage());
        }
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        String closeBy = remote ? "servidor" : "cliente";
        log("WARN", "Conexión cerrada por " + closeBy + " - Código: " + code + ", Razón: " + reason);

        // Auto-reconexión cada 5 segundos si el agente sigue corriendo
        if (running) {
            scheduleReconnect();
        }
    }

    @Override
    public void onError(Exception ex) {
        log("ERROR", "Error de WebSocket: " + ex.getMessage());

        if (ex.getCause() != null) {
            log("ERROR", "Causa: " + ex.getCause().getMessage());
        }

        // Intentar reconexión si hay error y el agente sigue corriendo
        if (running && !isOpen()) {
            scheduleReconnect();
        }
    }

    /**
     * Programa un reintento de conexión después del delay configurado.
     */
    private void scheduleReconnect() {
        if (!running) return;

        log("INFO", "Reintentando conexión en " + RECONNECT_DELAY_SECONDS + " segundos...");
        scheduler.schedule(() -> {
            if (running) {
                log("INFO", "Intentando reconexión...");
                try {
                    reconnect();
                } catch (Exception e) {
                    log("ERROR", "Error en reconexión: " + e.getMessage());
                    scheduleReconnect();
                }
            }
        }, RECONNECT_DELAY_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * Detiene el agente y cierra la conexión.
     */
    public void shutdown() {
        log("INFO", "Deteniendo agente...");
        running = false;

        try {
            closeBlocking();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }

        log("INFO", "Agente detenido correctamente");
    }

    /**
     * Registra un mensaje con timestamp.
     *
     * @param level   Nivel del log (INFO, WARN, ERROR, DEBUG)
     * @param message Mensaje a registrar
     */
    private static void log(String level, String message) {
        String timestamp = LocalDateTime.now().format(LOG_FORMAT);
        System.out.println(timestamp + " [" + level + "] " + message);
    }

    /**
     * Punto de entrada principal.
     * Lee configuración de variables de entorno o archivo properties.
     */
    public static void main(String[] args) {
        log("INFO", "============================================");
        log("INFO", "    POS PRINTER AGENT - LA PASADITA");
        log("INFO", "============================================");

        // Cargar configuración de archivo properties (si existe)
        Properties fileConfig = loadPropertiesFile(args.length > 0 ? args[0] : "config.properties");

        // Leer configuración con prioridad: ENV > properties > default
        String serverUrl = getConfig("SERVER_URL", "server.url", fileConfig, DEFAULT_SERVER_URL);
        String stationId = getConfig("STATION_ID", "station.id", fileConfig, DEFAULT_STATION_ID);
        String printerPath = getConfig("PRINTER_PATH", "printer.path", fileConfig, DEFAULT_PRINTER_PATH);
        String printerName = getConfig("PRINTER_NAME", "printer.name", fileConfig, "");
        String businessName = getConfig("BUSINESS_NAME", "business.name", fileConfig, "LA PASADITA");
        String businessAddress = getConfig("BUSINESS_ADDRESS", "business.address", fileConfig, "");
        String businessPhone = getConfig("BUSINESS_PHONE", "business.phone", fileConfig, "");

        // Configuración de báscula
        String scalePort = getConfig("SCALE_PORT", "scale.port", fileConfig, DEFAULT_SCALE_PORT);
        boolean scaleEnabled = Boolean.parseBoolean(getConfig("SCALE_ENABLED", "scale.enabled", fileConfig, String.valueOf(DEFAULT_SCALE_ENABLED)));
        boolean scaleAutoConnect = Boolean.parseBoolean(getConfig("SCALE_AUTO_CONNECT", "scale.autoConnect", fileConfig, String.valueOf(DEFAULT_SCALE_AUTO_CONNECT)));

        // Construir URL con stationId
        String fullUrl = serverUrl + "?stationId=" + stationId;

        log("INFO", "Configuración:");
        log("INFO", "  Station ID: " + stationId);
        log("INFO", "  Server URL: " + fullUrl);
        log("INFO", "  Sistema Operativo: " + (ESCPOSPrinter.isWindows() ? "Windows" : "Linux"));
        if (ESCPOSPrinter.isWindows()) {
            log("INFO", "  Printer Name (Windows): " + (printerName.isEmpty() ? "(no configurado)" : printerName));
        } else {
            log("INFO", "  Printer Path (Linux): " + printerPath);
        }
        log("INFO", "  Business: " + businessName);
        log("INFO", "  Scale Port: " + scalePort);
        log("INFO", "  Scale Enabled: " + scaleEnabled);
        log("INFO", "  Scale Auto-Connect: " + scaleAutoConnect);
        log("INFO", "============================================");

        // Verificar si se solicita página de prueba
        for (String arg : args) {
            if ("--test".equals(arg) || "-t".equals(arg)) {
                ESCPOSPrinter testPrinter = new ESCPOSPrinter(businessName, businessAddress, businessPhone, printerPath, printerName);
                try {
                    log("INFO", "Imprimiendo página de prueba...");
                    testPrinter.printTestPage();
                    log("INFO", "Página de prueba impresa correctamente");
                } catch (IOException e) {
                    log("ERROR", "Error imprimiendo página de prueba: " + e.getMessage());
                } catch (ESCPOSPrinter.PrinterException e) {
                    log("ERROR", "Error de impresora: " + e.getMessage());
                }
                return;
            }
        }

        // Crear instancias
        ESCPOSPrinter printer = new ESCPOSPrinter(businessName, businessAddress, businessPhone, printerPath, printerName);
        log("INFO", "Impresora disponible: " + printer.isAvailable());

        // Iniciar servidor REST de báscula
        ScaleRestServer scaleServer = null;
        if (scaleEnabled) {
            try {
                scaleServer = new ScaleRestServer(scalePort, scaleEnabled, scaleAutoConnect);
                scaleServer.start();
                log("INFO", "Servidor REST de báscula iniciado en http://localhost:8081");
            } catch (Exception e) {
                log("ERROR", "Error al iniciar servidor REST de báscula: " + e.getMessage());
            }
        }

        final ScaleRestServer finalScaleServer = scaleServer;

        try {
            URI serverUri = new URI(fullUrl);
            POSPrinterAgent agent = new POSPrinterAgent(serverUri, stationId, printer);

            // Agregar shutdown hook para cierre limpio
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                log("INFO", "Señal de cierre recibida");
                agent.shutdown();
                if (finalScaleServer != null) {
                    log("INFO", "Deteniendo servidor REST de báscula...");
                    finalScaleServer.stop();
                }
            }));

            // Conectar al servidor
            log("INFO", "Conectando al servidor...");
            agent.connect();

            // Mantener el proceso vivo
            while (agent.running) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

        } catch (URISyntaxException e) {
            log("ERROR", "URL inválida: " + fullUrl);
            log("ERROR", "Detalle: " + e.getMessage());
            System.exit(1);
        }
    }

    /**
     * Obtiene un valor de configuración con prioridad: ENV > properties > default.
     *
     * @param envKey       Nombre de la variable de entorno
     * @param propKey      Nombre de la propiedad en el archivo
     * @param props        Properties cargadas del archivo
     * @param defaultValue Valor por defecto
     * @return Valor de configuración
     */
    private static String getConfig(String envKey, String propKey, Properties props, String defaultValue) {
        // 1. Primero intentar variable de entorno
        String envValue = System.getenv(envKey);
        if (envValue != null && !envValue.isEmpty()) {
            return envValue;
        }

        // 2. Luego intentar archivo properties
        String propValue = props.getProperty(propKey);
        if (propValue != null && !propValue.isEmpty()) {
            return propValue;
        }

        // 3. Finalmente usar valor por defecto
        return defaultValue;
    }

    /**
     * Carga la configuración desde un archivo properties.
     *
     * @param configFile Ruta al archivo de configuración
     * @return Properties con la configuración (vacío si no existe)
     */
    private static Properties loadPropertiesFile(String configFile) {
        Properties props = new Properties();

        try (InputStream input = new FileInputStream(configFile)) {
            props.load(input);
            log("INFO", "Configuración cargada desde: " + configFile);
        } catch (IOException e) {
            log("INFO", "Archivo de configuración no encontrado, usando ENV/defaults");
        }

        return props;
    }
}

