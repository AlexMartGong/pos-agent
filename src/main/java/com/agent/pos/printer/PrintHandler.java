package com.agent.pos.printer;

import com.agent.pos.config.CorsUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

public class PrintHandler implements HttpHandler {

    private static final Logger logger = LoggerFactory.getLogger(PrintHandler.class);

    private final PrintMessageHandler messageHandler;
    private final ObjectMapper objectMapper;

    public PrintHandler(PrintMessageHandler messageHandler, ObjectMapper objectMapper) {
        this.messageHandler = messageHandler;
        this.objectMapper = objectMapper;
    }

    @Override
    public void handle(HttpExchange exchange) {
        try {
            if (CorsUtils.handlePreflightIfOptions(exchange)) {
                return;
            }

            CorsUtils.addCorsHeaders(exchange);

            if (!"POST".equals(exchange.getRequestMethod())) {
                sendJson(exchange, 405, errorBody());
                return;
            }

            String body = readBody(exchange);

            if (body.isBlank()) {
                sendJson(exchange, 200, errorBody("Body vacio"));
                return;
            }

            PrintResult result = messageHandler.handle(body);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("ticketId", result.ticketId());
            response.put("success", result.success());
            if (result.errorMessage() != null) {
                response.put("error", result.errorMessage());
            }

            sendJson(exchange, 200, response);

        } catch (Exception e) {
            logger.error("Error inesperado en PrintHandler: {}", e.getMessage(), e);
            try {
                Map<String, Object> response = new LinkedHashMap<>();
                response.put("ticketId", "-1");
                response.put("success", false);
                response.put("error", "Error interno del servidor");
                sendJson(exchange, 200, response);
            } catch (IOException ignored) {
            }
        }
    }

    private String readBody(HttpExchange exchange) throws IOException {
        try (InputStream is = exchange.getRequestBody()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private Map<String, Object> errorBody() {
        return errorBody("Metodo no permitido");
    }

    private Map<String, Object> errorBody(String message) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("ticketId", "-1");
        map.put("success", false);
        map.put("error", message);
        return map;
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