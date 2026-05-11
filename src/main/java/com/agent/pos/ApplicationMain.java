package com.agent.pos;

import com.agent.pos.config.AppConfig;
import com.agent.pos.printer.ESCPOSPrinter;
import com.agent.pos.printer.PrintMessageHandler;
import com.agent.pos.scale.ScaleRestServer;
import com.agent.pos.ws.POSWebSocketClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.net.URI;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class ApplicationMain {

    private static final DateTimeFormatter LOG_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public static void main(String[] args) {
        log("INFO", "============================================");
        log("INFO", "POS AGENT");
        log("INFO", "============================================");

        AppConfig config = AppConfig.load(args);

        config.logConfig();

        if (config.isTestMode()) {
            ESCPOSPrinter testPrinter = new ESCPOSPrinter(
                    config.getBusinessName(), config.getBusinessAddress(),
                    config.getBusinessPhone(), config.getPrinterPath(), config.getPrinterName());
            try {
                log("INFO", "Imprimiendo página de prueba...");
                testPrinter.printTestPage();
                log("INFO", "Página de prueba impresa correctamente");
            } catch (Exception e) {
                log("ERROR", "Error imprimiendo página de prueba: " + e.getMessage());
            }
            return;
        }

        ESCPOSPrinter printer = new ESCPOSPrinter(
                config.getBusinessName(), config.getBusinessAddress(),
                config.getBusinessPhone(), config.getPrinterPath(), config.getPrinterName());
        log("INFO", "Impresora disponible: " + printer.isAvailable());

        ScaleRestServer scaleServer = null;
        try {
            scaleServer = new ScaleRestServer(
                    config.getScalePort(), config.isScaleEnabled(),
                    config.isScaleAutoConnect(), config.getStationId());
            scaleServer.start();
            log("INFO", "Servidor REST iniciado en http://localhost:8081");
        } catch (Exception e) {
            log("ERROR", "Error al iniciar servidor REST: " + e.getMessage());
        }

        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());

        PrintMessageHandler messageHandler = new PrintMessageHandler(printer, objectMapper);

        final ScaleRestServer finalScaleServer = scaleServer;

        try {
            URI serverUri = new URI(config.getFullWsUrl());
            POSWebSocketClient client = new POSWebSocketClient(serverUri, config.getStationId(), messageHandler, objectMapper);

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                log("INFO", "Señal de cierre recibida");
                client.shutdown();
                if (finalScaleServer != null) {
                    log("INFO", "Deteniendo servidor REST de báscula...");
                    finalScaleServer.stop();
                }
            }));

            log("INFO", "Conectando al servidor...");
            client.connect();

            try {
                client.awaitTermination();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

        } catch (Exception e) {
            log("ERROR", "URL inválida: " + config.getFullWsUrl());
            log("ERROR", "Detalle: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void log(String level, String message) {
        String timestamp = LocalDateTime.now().format(LOG_FORMAT);
        System.out.println(timestamp + " [" + level + "] " + message);
    }
}