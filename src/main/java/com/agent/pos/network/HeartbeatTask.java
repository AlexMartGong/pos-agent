package com.agent.pos.network;

import com.agent.pos.ApplicationMain;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicBoolean;

public class HeartbeatTask implements Runnable {

    private static final DateTimeFormatter LOG_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final Path UPDATE_FILE = Path.of("pos-agent-next.jar");

    private final String saasApiUrl;
    private final String stationId;
    private final String agentApiKey;
    private final int httpPort;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicBoolean updateInProgress = new AtomicBoolean(false);

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
            String jsonPayload = "{\"url\":\"" + agentUrl
                    + "\",\"currentVersion\":\"" + ApplicationMain.VERSION + "\"}";

            String targetUrl = saasApiUrl + "/agent/stations/" + stationId + "/url";

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
                if (response.statusCode() == 200) {
                    checkForUpdate(response.body());
                }
            } else {
                log("WARN", "Heartbeat rechazado (HTTP " + response.statusCode() + "): " + response.body());
            }
        } catch (Exception e) {
            log("WARN", "No se pudo reportar el heartbeat al SaaS: " + e.getMessage());
        }
    }

    /** Parsea la respuesta del SaaS y dispara la actualizacion si procede. Aislado: nunca propaga. */
    private void checkForUpdate(String body) {
        if (body == null || body.isBlank()) {
            return;
        }
        try {
            AgentUpdateResponse upd = objectMapper.readValue(body, AgentUpdateResponse.class);
            if (upd.updateAvailable() && upd.downloadUrl() != null && !upd.downloadUrl().isBlank()) {
                startUpdate(upd);
            }
        } catch (Exception e) {
            log("WARN", "No se pudo procesar la respuesta de actualizacion: " + e.getMessage());
        }
    }

    /** Descarga + valida SHA-256 en un hilo daemon. Si el hash coincide, sale != 0 para que WinSW relance el wrapper. */
    private void startUpdate(AgentUpdateResponse upd) {
        if (!updateInProgress.compareAndSet(false, true)) {
            log("INFO", "Actualizacion ya en curso, se omite esta verificacion.");
            return;
        }
        Thread t = new Thread(() -> {
            try {
                log("INFO", "Actualizacion disponible. Descargando desde " + upd.downloadUrl());
                HttpRequest dl = HttpRequest.newBuilder()
                        .uri(URI.create(upd.downloadUrl()))
                        .timeout(Duration.ofSeconds(60))
                        .GET()
                        .build();
                httpClient.send(dl, HttpResponse.BodyHandlers.ofFile(UPDATE_FILE));

                String calc = sha256Hex();
                if (calc.equalsIgnoreCase(upd.sha256())) {
                    log("INFO", "Hash verificado. Reiniciando para aplicar actualizacion -> " + upd.sha256());
                    System.exit(1);
                } else {
                    log("WARN", "Hash NO coincide (esperado " + upd.sha256() + ", calculado " + calc
                            + "). Archivo corrupto/alterado: se descarta y se mantiene la version actual.");
                    Files.deleteIfExists(UPDATE_FILE);
                }
            } catch (Exception e) {
                log("WARN", "Fallo la actualizacion del agente: " + e.getMessage());
                try {
                    Files.deleteIfExists(UPDATE_FILE);
                } catch (Exception ignored) {
                    // archivo parcial inaccesible; el proximo intento lo sobreescribe
                }
            } finally {
                updateInProgress.set(false);
            }
        }, "agent-updater");
        t.setDaemon(true);
        t.start();
    }

    private static String sha256Hex() throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(HeartbeatTask.UPDATE_FILE)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                md.update(buf, 0, n);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record AgentUpdateResponse(boolean updateAvailable, String downloadUrl, String sha256) {}

    private static void log(String level, String message) {
        String timestamp = LocalDateTime.now().format(LOG_FORMAT);
        System.out.println(timestamp + " [" + level + "] " + message);
    }
}