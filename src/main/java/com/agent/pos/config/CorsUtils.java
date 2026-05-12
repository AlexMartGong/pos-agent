package com.agent.pos.config;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;

public class CorsUtils {

    private CorsUtils() {
    }

    public static void addCorsHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type, Authorization, Accept, Origin");
    }

    public static boolean handlePreflightIfOptions(HttpExchange exchange) throws IOException {
        if ("OPTIONS".equals(exchange.getRequestMethod())) {
            addCorsHeaders(exchange);
            exchange.getResponseHeaders().add("Access-Control-Max-Age", "86400");
            exchange.sendResponseHeaders(204, -1);
            return true;
        }
        return false;
    }
}