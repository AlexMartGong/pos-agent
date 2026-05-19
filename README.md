# POS Agent

Local Java agent that bridges the cloud POS (La Pasadita / Tequila SAAS) and the client's physical hardware on each store. Exposes a small REST API on the LAN so the React frontend (running on any tablet/phone/PC) can print tickets and read weight from a scale.

No Spring, no WebSocket, no web framework — only native `com.sun.net.httpserver` + Jackson + jSerialComm + SLF4J.

## Requirements

- Java 17+
- Maven 3.6+
- ESC/POS thermal printer (USB, raw Linux device, Windows `javax.print`, or network on port 9100)
- Optional: Torrey-compatible electronic scale on a serial port

## Build

```bash
mvn clean package
```

Produces `target/pos-agent.jar` (fat JAR with all dependencies). Main class: `com.agent.pos.ApplicationMain`.

## Run

```bash
# Default config (config.properties next to the JAR)
java -jar target/pos-agent.jar

# Custom config file
java -jar target/pos-agent.jar /path/to/config.properties

# Print a test page and exit (validates printer connectivity)
java -jar target/pos-agent.jar --test     # or -t
```

The agent binds to `0.0.0.0:8081` so devices on the same LAN can reach it.

## Configuration

Precedence: **Environment variables > `config.properties` > hardcoded defaults.**

```properties
# HTTP
http.port=8081

# Unique station id (required for SaaS heartbeat; blank = heartbeat disabled)
station.id=09ec9299-154b-4f73-ab14-1e2bc8fee5ac

# Printer
# Linux: /dev/usb/lp0, /dev/usb/lp5...
# Windows: printer name visible to javax.print
# Network: an IPv4 address routes directly via Socket
printer.path=/dev/usb/lp0
printer.port=9100
# Ethernet fallback used if the cable path above fails
printer.network_ip=192.168.1.100

# Business header on tickets
business.name=LA PASADITA
business.address=...
business.phone=...

# Scale (Torrey, jSerialComm)
scale.port=/dev/ttyACM0          # or COM3 on Windows
scale.enabled=true
scale.autoConnect=true

# SaaS heartbeat (self-discovery)
saas.api.url=http://localhost:8080/api/v1
agent.api.key=default-agent-secret
```

Matching env vars: `HTTP_PORT`, `STATION_ID`, `PRINTER_PATH`, `PRINTER_PORT`, `PRINTER_NETWORK_IP`, `BUSINESS_NAME`, `BUSINESS_ADDRESS`, `BUSINESS_PHONE`, `SCALE_PORT`, `SCALE_ENABLED`, `SCALE_AUTO_CONNECT`, `SAAS_API_URL`, `AGENT_API_KEY`.

## HTTP API

| Method | Path | Notes |
|---|---|---|
| `POST` | `/api/printer/print` | Body = `TicketDTO` JSON. **Always returns 200** with `{ticketId, success, error?}` |
| `GET`  | `/api/health` | `{"status":"UP","stationId":"..."}` |
| `GET`  | `/api/station` | Station info |
| `GET`  | `/api/scale/weight` | 200 ok, 503 disabled/disconnected, 500 read error |
| `GET`  | `/api/scale/status` | Connection state |
| `POST` | `/api/scale/connect` | 200 ok, 500 fail, 503 disabled |
| `POST` | `/api/scale/disconnect` | |
| `GET`  | `/api/scale/ports` | Available serial ports |

All endpoints respond to CORS preflight (`OPTIONS` → 204, `Max-Age: 86400`).

### Ticket types

The print endpoint auto-detects the type from `TicketDTO`:

- **Cash register** (`deliveryOrderId == null`): opens cash drawer, prints header + details + totals, cuts paper.
- **Delivery** (`deliveryOrderId != null` and `deliveryAddress` non-empty): **no drawer**, adds `PEDIDO A DOMICILIO` header, customer data block, `Atendio` / `Forma de Pago` fields, `PEDIDO #`.

Ticket width is exactly **48 chars**. Detail columns: `CANT(5) PRODUCTO(24) PRECIO(8) TOTAL(8)` + 3 spaces. Product names longer than 24 chars wrap to continuation lines. Text is encoded as `ISO-8859-1` with PC850 charset.

## Printer transport

- `printer.path` matching IPv4 (`192.168.x.x`) → direct network via `java.net.Socket` to `printer.path:printer.port` (5s connect timeout).
- Otherwise → OS mode (Linux: raw device file; Windows: `javax.print`).
- If `printer.network_ip` is also set and `printer.path` is not an IP, cable is tried first; on failure the agent logs a `[WARN]` and falls back to Ethernet at `printer.network_ip:printer.port`.

## Linux permissions

USB printers and serial ports require group access:

```bash
sudo usermod -a -G lp,dialout $USER     # log out + back in
# or, ad hoc:
sudo chmod 666 /dev/usb/lp0
sudo chmod 666 /dev/ttyACM0
```

## Heartbeat (self-discovery)

If `station.id` is set, the agent reports its URL to the SaaS backend every 5 min:

```
PUT  {saas.api.url}/agent/stations/{station.id}/url
Header: X-Agent-Key: {agent.api.key}
Body:   {"url":"http://<lan-ip>:<http.port>"}
```

The LAN IP is resolved via a `DatagramSocket` connected to `8.8.8.8:10002` (no real packets sent) so the OS routing table returns the actual interface IP — `InetAddress.getLocalHost()` alone returns `127.0.1.1` on many Linux distros because of `/etc/hosts`. Falls back to `getLocalHost().getHostAddress()` if no network is available. All failures are caught and logged; the agent never crashes from a heartbeat error.

If `station.id` is blank, heartbeat is disabled and a WARN is logged at startup.

## Packaging / service

- **Linux .deb installer:** `./build-installer.sh` (uses `jpackage`, requires `JAVA_HOME` and JDK 14+).
- **Windows service:** WinSW descriptor in `service/pos-printer-agent.xml` plus `service/install-service.bat` / `uninstall-service.bat`.

## Project layout

```
src/main/java/com/agent/pos/
├── ApplicationMain.java        # entry point, server wiring, shutdown hook, heartbeat scheduler
├── config/
│   ├── AppConfig.java          # env > properties > defaults, builder + load(args)
│   └── CorsUtils.java
├── printer/
│   ├── PrintHandler.java       # POST /api/printer/print
│   ├── PrintMessageHandler.java
│   ├── ESCPOSPrinter.java      # raw ESC/POS bytes, network/Linux/Windows transports
│   └── PrintResult.java
├── scale/
│   ├── ScaleRestServer.java    # /api/scale/* + /api/station
│   ├── TorreyScaleController.java  # jSerialComm 9600 8N1, 100 ms polling
│   └── HealthHandler.java      # GET /api/health
├── dto/
│   ├── TicketDTO.java
│   └── SaleDetailDTO.java
└── network/
    └── HeartbeatTask.java
```

## Testing

There are no automated tests. Validate changes by:

1. `mvn clean package`
2. `java -jar target/pos-agent.jar --test` (prints a test page if the printer is reachable)
3. `curl -i http://localhost:8081/api/health`
4. Hit `POST /api/printer/print` with a sample `TicketDTO` body.
