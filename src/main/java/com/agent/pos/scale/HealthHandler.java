package com.agent.pos.scale;

import com.agent.pos.config.CorsUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

public class HealthHandler implements HttpHandler {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final String stationId;

    public HealthHandler(String stationId) {
        this.stationId = stationId;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (CorsUtils.handlePreflightIfOptions(exchange)) {
            return;
        }

        CorsUtils.addCorsHeaders(exchange);

        if (!"GET".equals(exchange.getRequestMethod())) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("success", false);
            error.put("error", "Metodo no permitido");
            sendJson(exchange, 405, error);
            return;
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "UP");
        response.put("stationId", stationId);
        sendJson(exchange, 200, response);
    }

    private void sendJson(HttpExchange exchange, int statusCode, Map<String, Object> data) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        String json = objectMapper.writeValueAsString(data);
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}