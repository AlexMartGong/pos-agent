package com.agent.pos.ws;

import com.agent.pos.printer.PrintMessageHandler;
import com.agent.pos.printer.PrintResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class POSWebSocketClient extends WebSocketClient {

    private static final int RECONNECT_DELAY_SECONDS = 5;
    private static final DateTimeFormatter LOG_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final String stationId;
    private final PrintMessageHandler messageHandler;
    private final ObjectMapper objectMapper;
    private final ScheduledExecutorService scheduler;
    private volatile boolean running = true;
    private final CountDownLatch shutdownLatch = new CountDownLatch(1);

    public POSWebSocketClient(URI serverUri, String stationId, PrintMessageHandler messageHandler, ObjectMapper objectMapper) {
        super(serverUri);
        this.stationId = stationId;
        this.messageHandler = messageHandler;
        this.objectMapper = objectMapper;
        this.scheduler = Executors.newSingleThreadScheduledExecutor();
    }

    @Override
    public void onOpen(ServerHandshake handshake) {
        log("INFO", "Conexión establecida con el servidor");
        log("INFO", "Esperando tickets para imprimir...");

        try {
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("type", "CONNECTED");
            msg.put("stationId", stationId);
            msg.put("timestamp", LocalDateTime.now().format(LOG_FORMAT));
            send(objectMapper.writeValueAsString(msg));
            log("INFO", "Mensaje de conexión enviado al servidor");
        } catch (Exception e) {
            log("ERROR", "No se pudo enviar mensaje de conexión: " + e.getMessage());
        }
    }

    @Override
    public void onMessage(String message) {
        PrintResult result = messageHandler.handle(message);
        sendPrintConfirmation(result);
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        String closeBy = remote ? "servidor" : "cliente";
        log("WARN", "Conexión cerrada por " + closeBy + " - Código: " + code + ", Razón: " + reason);

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

        if (running && !isOpen()) {
            scheduleReconnect();
        }
    }

    private void sendPrintConfirmation(PrintResult result) {
        try {
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("type", "PRINT_RESULT");
            msg.put("ticketId", result.ticketId());
            msg.put("success", result.success());
            msg.put("stationId", stationId);
            msg.put("timestamp", LocalDateTime.now().format(LOG_FORMAT));
            if (result.errorMessage() != null) {
                msg.put("error", result.errorMessage());
            }

            send(objectMapper.writeValueAsString(msg));
            log("INFO", "Confirmación enviada - Ticket #" + result.ticketId() + ": " + (result.success() ? "OK" : "FALLO"));
        } catch (Exception e) {
            log("ERROR", "No se pudo enviar confirmación: " + e.getMessage());
        }
    }

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

    public void shutdown() {
        log("INFO", "Deteniendo agente...");
        running = false;
        shutdownLatch.countDown();

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

    public void awaitTermination() throws InterruptedException {
        shutdownLatch.await();
    }

    private static void log(String level, String message) {
        String timestamp = LocalDateTime.now().format(LOG_FORMAT);
        System.out.println(timestamp + " [" + level + "] " + message);
    }
}