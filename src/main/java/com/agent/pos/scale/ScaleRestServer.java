package com.agent.pos.scale;

import com.agent.pos.config.CorsUtils;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public class ScaleRestServer {

    private static final Logger logger = LoggerFactory.getLogger(ScaleRestServer.class);

    private final HttpServer server;
    private final ObjectMapper objectMapper;

    private TorreyScaleController scaleController;

    private final String scalePort;
    private final boolean scaleEnabled;
    private final boolean autoConnect;
    private final String stationId;

    public ScaleRestServer(HttpServer server, String scalePort, boolean scaleEnabled, boolean autoConnect, String stationId) {
        this.server = server;
        this.scalePort = scalePort;
        this.scaleEnabled = scaleEnabled;
        this.autoConnect = autoConnect;
        this.stationId = stationId;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.configure(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN, true);
    }

    public void start() {
        logger.info("Registrando endpoints REST de báscula...");

        server.createContext("/api/scale/weight", new WeightHandler());
        server.createContext("/api/scale/status", new StatusHandler());
        server.createContext("/api/scale/connect", new ConnectHandler());
        server.createContext("/api/scale/disconnect", new DisconnectHandler());
        server.createContext("/api/scale/ports", new PortsHandler());
        server.createContext("/api/station", new StationHandler());

        if (scaleEnabled) {
            logger.info("Báscula habilitada en puerto: {}", scalePort);
            scaleController = new TorreyScaleController(scalePort);

            if (autoConnect) {
                logger.info("Auto-conectando con báscula...");
                scaleController.connect();
            }
        } else {
            logger.warn("Báscula deshabilitada en configuración");
        }

        logger.info("Endpoints de báscula registrados");
    }

    public void stop() {
        if (scaleController != null && scaleController.isConnected()) {
            logger.info("Desconectando báscula...");
            scaleController.disconnect();
        }
    }

    private class WeightHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (CorsUtils.handlePreflightIfOptions(exchange)) return;

            if (!"GET".equals(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, createError());
                return;
            }

            Map<String, Object> response = new HashMap<>();

            if (!scaleEnabled) {
                response.put("success", false);
                response.put("error", "Báscula no habilitada");
                sendResponse(exchange, 503, response);
                return;
            }

            if (scaleController == null || !scaleController.isConnected()) {
                response.put("success", false);
                response.put("error", "Báscula no conectada");
                response.put("connected", false);
                sendResponse(exchange, 503, response);
                return;
            }

            try {
                TorreyScaleController.WeightReading reading = scaleController.readWeight();

                if (reading.success()) {
                    response.put("success", true);
                    response.put("weight", reading.weight());
                    response.put("unit", reading.unit());
                    response.put("stable", reading.stable());
                    response.put("connected", true);
                    sendResponse(exchange, 200, response);
                } else {
                    response.put("success", false);
                    response.put("error", reading.errorMessage());
                    response.put("connected", scaleController.isConnected());
                    sendResponse(exchange, 500, response);
                }

            } catch (Exception e) {
                logger.error("Error al leer peso: {}", e.getMessage(), e);
                response.put("success", false);
                response.put("error", "Error interno al leer peso");
                response.put("connected", false);
                sendResponse(exchange, 500, response);
            }
        }
    }

    private class StatusHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (CorsUtils.handlePreflightIfOptions(exchange)) return;

            if (!"GET".equals(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, createError());
                return;
            }

            Map<String, Object> response = new HashMap<>();
            response.put("enabled", scaleEnabled);
            response.put("port", scalePort);

            if (scaleController != null) {
                response.put("connected", scaleController.isConnected());
                response.put("portName", scaleController.getPortName());
            } else {
                response.put("connected", false);
                response.put("portName", null);
            }

            sendResponse(exchange, 200, response);
        }
    }

    private class ConnectHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (CorsUtils.handlePreflightIfOptions(exchange)) return;

            if (!"POST".equals(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, createError());
                return;
            }

            Map<String, Object> response = new HashMap<>();

            if (!scaleEnabled) {
                response.put("success", false);
                response.put("error", "Báscula no habilitada en configuración");
                sendResponse(exchange, 503, response);
                return;
            }

            if (scaleController == null) {
                scaleController = new TorreyScaleController(scalePort);
            }

            if (scaleController.isConnected()) {
                response.put("success", true);
                response.put("message", "Ya conectado");
                response.put("connected", true);
                sendResponse(exchange, 200, response);
                return;
            }

            boolean connected = scaleController.connect();

            response.put("success", connected);
            response.put("connected", connected);
            response.put("message", connected ? "Conectado exitosamente" : "Error al conectar");

            if (!connected) {
                response.put("error", "No se pudo establecer conexión con la báscula");
                sendResponse(exchange, 500, response);
            } else {
                sendResponse(exchange, 200, response);
            }
        }
    }

    private class DisconnectHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (CorsUtils.handlePreflightIfOptions(exchange)) return;

            if (!"POST".equals(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, createError());
                return;
            }

            Map<String, Object> response = new HashMap<>();

            if (scaleController != null) {
                scaleController.disconnect();
                response.put("success", true);
                response.put("message", "Desconectado exitosamente");
                response.put("connected", false);
            } else {
                response.put("success", false);
                response.put("message", "No hay controlador de báscula");
                response.put("connected", false);
            }

            sendResponse(exchange, 200, response);
        }
    }

    private class PortsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (CorsUtils.handlePreflightIfOptions(exchange)) return;

            if (!"GET".equals(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, createError());
                return;
            }

            Map<String, Object> response = new HashMap<>();

            try {
                String[] ports = TorreyScaleController.getAvailablePorts();
                response.put("success", true);
                response.put("ports", ports);
                response.put("count", ports.length);
                response.put("currentPort", scalePort);

                sendResponse(exchange, 200, response);

            } catch (Exception e) {
                logger.error("Error al listar puertos: {}", e.getMessage(), e);
                response.put("success", false);
                response.put("error", "Error interno al listar puertos");
                sendResponse(exchange, 500, response);
            }
        }
    }

    private class StationHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (CorsUtils.handlePreflightIfOptions(exchange)) return;

            if (!"GET".equals(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, createError());
                return;
            }

            Map<String, Object> response = new HashMap<>();
            response.put("stationId", stationId);
            sendResponse(exchange, 200, response);
        }
    }

    private void sendResponse(HttpExchange exchange, int statusCode, Map<String, Object> data) {
        try {
            CorsUtils.addCorsHeaders(exchange);
            exchange.getResponseHeaders().add("Content-Type", "application/json");

            String jsonResponse = objectMapper.writeValueAsString(data);
            byte[] responseBytes = jsonResponse.getBytes(StandardCharsets.UTF_8);

            exchange.sendResponseHeaders(statusCode, responseBytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBytes);
            }
        } catch (IOException e) {
            if (!e.getMessage().contains("Tubería rota") && !e.getMessage().contains("Broken pipe")) {
                logger.warn("Error enviando respuesta: {}", e.getMessage());
            }
        }
    }

    private Map<String, Object> createError() {
        Map<String, Object> error = new HashMap<>();
        error.put("success", false);
        error.put("error", "Método no permitido");
        return error;
    }
}