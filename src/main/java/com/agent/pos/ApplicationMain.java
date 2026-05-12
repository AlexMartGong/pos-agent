package com.agent.pos;

import com.agent.pos.config.AppConfig;
import com.agent.pos.printer.ESCPOSPrinter;
import com.agent.pos.printer.PrintHandler;
import com.agent.pos.printer.PrintMessageHandler;
import com.agent.pos.scale.HealthHandler;
import com.agent.pos.scale.ScaleRestServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.Executors;

public class ApplicationMain {

    private static final DateTimeFormatter LOG_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public static void main(String[] args) throws Exception {
        log("INFO", "============================================");
        log("INFO", "POS AGENT (REST)");
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

        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", config.getHttpPort()), 0);
        server.setExecutor(Executors.newFixedThreadPool(8));

        ESCPOSPrinter printer = new ESCPOSPrinter(
                config.getBusinessName(), config.getBusinessAddress(),
                config.getBusinessPhone(), config.getPrinterPath(), config.getPrinterName());
        log("INFO", "Impresora disponible: " + printer.isAvailable());

        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());

        PrintMessageHandler messageHandler = new PrintMessageHandler(printer, objectMapper);

        ScaleRestServer scaleServer = new ScaleRestServer(
                server, config.getScalePort(), config.isScaleEnabled(),
                config.isScaleAutoConnect(), config.getStationId());
        scaleServer.start();

        server.createContext("/api/printer/print", new PrintHandler(messageHandler, objectMapper));
        server.createContext("/api/health", new HealthHandler(config.getStationId()));

        server.start();
        log("INFO", "Servidor HTTP iniciado en http://0.0.0.0:" + config.getHttpPort());
        log("INFO", "Endpoints disponibles:");
        log("INFO", "  POST /api/printer/print  - Imprimir ticket");
        log("INFO", "  GET  /api/health           - Health check");
        log("INFO", "  GET  /api/scale/weight      - Lectura de báscula");
        log("INFO", "  GET  /api/scale/status      - Estado de báscula");
        log("INFO", "  POST /api/scale/connect     - Conectar báscula");
        log("INFO", "  POST /api/scale/disconnect  - Desconectar báscula");
        log("INFO", "  GET  /api/scale/ports       - Puertos serie disponibles");
        log("INFO", "  GET  /api/station           - ID de estación");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log("INFO", "Señal de cierre recibida");
            server.stop(2);
            scaleServer.stop();
            log("INFO", "Agente detenido correctamente");
        }));

        Thread.currentThread().join();
    }

    private static void log(String level, String message) {
        String timestamp = LocalDateTime.now().format(LOG_FORMAT);
        System.out.println(timestamp + " [" + level + "] " + message);
    }
}