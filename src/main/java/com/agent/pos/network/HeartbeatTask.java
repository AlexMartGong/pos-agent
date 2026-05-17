package com.agent.pos.network;

import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class HeartbeatTask implements Runnable {

    private static final DateTimeFormatter LOG_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final String saasApiUrl;
    private final String stationId;
    private final String agentApiKey;
    private final int httpPort;
    private final HttpClient httpClient;

    public HeartbeatTask(String saasApiUrl, String stationId, String agentApiKey, int httpPort) {
        this.saasApiUrl = saasApiUrl;
        this.stationId = stationId;
        this.agentApiKey = agentApiKey;
        this.httpPort = httpPort;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @Override
    public void run() {
        try {
            String localIp;
            try (java.net.DatagramSocket socket = new java.net.DatagramSocket()) {
                socket.connect(java.net.InetAddress.getByName("8.8.8.8"), 10002);
                localIp = socket.getLocalAddress().getHostAddress();
            } catch (Exception ex) {
                localIp = InetAddress.getLocalHost().getHostAddress();
            }
            String agentUrl = "http://" + localIp + ":" + httpPort;
            String jsonPayload = "{\"url\":\"" + agentUrl + "\"}";

            String targetUrl = saasApiUrl + "/agent/stores/" + stationId + "/url";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(targetUrl))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .header("X-Agent-Key", agentApiKey)
                    .PUT(HttpRequest.BodyPublishers.ofString(jsonPayload))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                log("INFO", "Heartbeat exitoso: " + agentUrl + " reportado a " + targetUrl);
            } else {
                log("WARN", "Heartbeat rechazado (HTTP " + response.statusCode() + "): " + response.body());
            }
        } catch (Exception e) {
            log("WARN", "No se pudo reportar el heartbeat al SaaS: " + e.getMessage());
        }
    }

    private static void log(String level, String message) {
        String timestamp = LocalDateTime.now().format(LOG_FORMAT);
        System.out.println(timestamp + " [" + level + "] " + message);
    }
}