package com.agent.pos.config;

import com.agent.pos.printer.ESCPOSPrinter;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Properties;

public class AppConfig {

    private static final int DEFAULT_HTTP_PORT = 8081;
    private static final String DEFAULT_STATION_ID = "POS1";
    private static final String DEFAULT_PRINTER_PATH = "/dev/usb/lp0";
    private static final int DEFAULT_PRINTER_PORT = 9100;
    private static final String DEFAULT_SCALE_PORT = "/dev/ttyACM0";
    private static final boolean DEFAULT_SCALE_ENABLED = true;
    private static final boolean DEFAULT_SCALE_AUTO_CONNECT = true;

    private static final DateTimeFormatter LOG_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final int httpPort;
    private final String stationId;
    private final String printerPath;
    private final int printerPort;
    private final String printerName;
    private final String businessName;
    private final String businessAddress;
    private final String businessPhone;
    private final String scalePort;
    private final boolean scaleEnabled;
    private final boolean scaleAutoConnect;
    private final boolean testMode;

    private AppConfig(Builder builder) {
        this.httpPort = builder.httpPort;
        this.stationId = builder.stationId;
        this.printerPath = builder.printerPath;
        this.printerPort = builder.printerPort;
        this.printerName = builder.printerName;
        this.businessName = builder.businessName;
        this.businessAddress = builder.businessAddress;
        this.businessPhone = builder.businessPhone;
        this.scalePort = builder.scalePort;
        this.scaleEnabled = builder.scaleEnabled;
        this.scaleAutoConnect = builder.scaleAutoConnect;
        this.testMode = builder.testMode;
    }

    public int getHttpPort() { return httpPort; }
    public String getStationId() { return stationId; }
    public String getPrinterPath() { return printerPath; }
    public int getPrinterPort() { return printerPort; }
    public String getPrinterName() { return printerName; }
    public String getBusinessName() { return businessName; }
    public String getBusinessAddress() { return businessAddress; }
    public String getBusinessPhone() { return businessPhone; }
    public String getScalePort() { return scalePort; }
    public boolean isScaleEnabled() { return scaleEnabled; }
    public boolean isScaleAutoConnect() { return scaleAutoConnect; }
    public boolean isTestMode() { return testMode; }

    public static AppConfig load(String[] args) {
        boolean testMode = false;
        String configFilePath = "config.properties";

        for (String arg : args) {
            if ("--test".equals(arg) || "-t".equals(arg)) {
                testMode = true;
            } else {
                configFilePath = arg;
            }
        }

        Properties fileConfig = loadPropertiesFile(configFilePath);

        return new Builder()
                .httpPort(Integer.parseInt(resolve("HTTP_PORT", "http.port", fileConfig, String.valueOf(DEFAULT_HTTP_PORT))))
                .stationId(resolve("STATION_ID", "station.id", fileConfig, DEFAULT_STATION_ID))
                .printerPath(resolve("PRINTER_PATH", "printer.path", fileConfig, DEFAULT_PRINTER_PATH))
                .printerPort(Integer.parseInt(resolve("PRINTER_PORT", "printer.port", fileConfig, String.valueOf(DEFAULT_PRINTER_PORT))))
                .printerName(resolve("PRINTER_NAME", "printer.name", fileConfig, ""))
                .businessName(resolve("BUSINESS_NAME", "business.name", fileConfig, "LA PASADITA"))
                .businessAddress(resolve("BUSINESS_ADDRESS", "business.address", fileConfig, ""))
                .businessPhone(resolve("BUSINESS_PHONE", "business.phone", fileConfig, ""))
                .scalePort(resolve("SCALE_PORT", "scale.port", fileConfig, DEFAULT_SCALE_PORT))
                .scaleEnabled(Boolean.parseBoolean(resolve("SCALE_ENABLED", "scale.enabled", fileConfig, String.valueOf(DEFAULT_SCALE_ENABLED))))
                .scaleAutoConnect(Boolean.parseBoolean(resolve("SCALE_AUTO_CONNECT", "scale.autoConnect", fileConfig, String.valueOf(DEFAULT_SCALE_AUTO_CONNECT))))
                .testMode(testMode)
                .build();
    }

    private static String resolve(String envKey, String propKey, Properties props, String defaultValue) {
        String envValue = System.getenv(envKey);
        if (envValue != null && !envValue.isEmpty()) {
            return envValue;
        }

        String propValue = props.getProperty(propKey);
        if (propValue != null && !propValue.isEmpty()) {
            return propValue;
        }

        return defaultValue;
    }

    private static Properties loadPropertiesFile(String configFile) {
        Properties props = new Properties();

        try (InputStream input = new FileInputStream(configFile)) {
            props.load(input);
            log("Configuracion cargada desde: " + configFile);
        } catch (IOException e) {
            log("Archivo de configuracion no encontrado, usando ENV/defaults");
        }

        return props;
    }

    public void logConfig() {
        log("Configuracion:");
        log("  Station ID: " + stationId);
        log("  HTTP Port: " + httpPort);
        log("  Sistema Operativo: " + (ESCPOSPrinter.isWindows() ? "Windows" : "Linux"));
        if (ESCPOSPrinter.isNetworkPrinterPath(printerPath)) {
            log("  Impresora (Red): " + printerPath + ":" + printerPort);
        } else if (ESCPOSPrinter.isWindows()) {
            log("  Printer Name (Windows): " + (printerName.isEmpty() ? "(no configurado)" : printerName));
        } else {
            log("  Printer Path (Linux): " + printerPath);
        }
        log("  Business: " + businessName);
        log("  Scale Port: " + scalePort);
        log("  Scale Enabled: " + scaleEnabled);
        log("  Scale Auto-Connect: " + scaleAutoConnect);
        log("============================================");
    }

    private static void log(String message) {
        String timestamp = LocalDateTime.now().format(LOG_FORMAT);
        System.out.println(timestamp + " [" + "INFO" + "] " + message);
    }

    public static class Builder {
        private int httpPort = DEFAULT_HTTP_PORT;
        private String stationId;
        private String printerPath;
        private int printerPort = DEFAULT_PRINTER_PORT;
        private String printerName;
        private String businessName;
        private String businessAddress;
        private String businessPhone;
        private String scalePort;
        private boolean scaleEnabled;
        private boolean scaleAutoConnect;
        private boolean testMode;

        public Builder httpPort(int httpPort) { this.httpPort = httpPort; return this; }
        public Builder stationId(String stationId) { this.stationId = stationId; return this; }
        public Builder printerPath(String printerPath) { this.printerPath = printerPath; return this; }
        public Builder printerPort(int printerPort) { this.printerPort = printerPort; return this; }
        public Builder printerName(String printerName) { this.printerName = printerName; return this; }
        public Builder businessName(String businessName) { this.businessName = businessName; return this; }
        public Builder businessAddress(String businessAddress) { this.businessAddress = businessAddress; return this; }
        public Builder businessPhone(String businessPhone) { this.businessPhone = businessPhone; return this; }
        public Builder scalePort(String scalePort) { this.scalePort = scalePort; return this; }
        public Builder scaleEnabled(boolean scaleEnabled) { this.scaleEnabled = scaleEnabled; return this; }
        public Builder scaleAutoConnect(boolean scaleAutoConnect) { this.scaleAutoConnect = scaleAutoConnect; return this; }
        public Builder testMode(boolean testMode) { this.testMode = testMode; return this; }

        public AppConfig build() {
            return new AppConfig(this);
        }
    }
}