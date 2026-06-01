# AGENTS.md

## Build & Run

```bash
mvn clean package                    # Fat JAR → target/pos-agent.jar
java -jar target/pos-agent.jar       # Run agent
java -jar target/pos-agent.jar --test  # Print test page, then exit
java -jar target/pos-agent.jar /path/to/config.properties  # Custom config file
```

- Java 17+, Maven 3.6+
- Main class: `com.agent.pos.ApplicationMain`
- Assembly plugin produces `target/pos-agent.jar` (finalName pos-agent, appendAssemblyId false)
- **No automated tests exist.** Do not assume `mvn test` can verify anything.
- **`build-installer.sh` is stale** — references old class `com.pasadita.pos.POSPrinterAgent` and old JAR name. Do not trust it.
- **`README.md` is stale** — describes a WebSocket architecture that no longer exists. Ignore it.

## Architecture

Native `com.sun.net.httpserver.HttpServer` on `0.0.0.0:8081`. No web framework.

Endpoints (all with CORS `Access-Control-Allow-Origin: *`):
- `POST /api/printer/print` → `PrintHandler`
- `GET /api/health` → `HealthHandler`
- `/api/scale/*` and `/api/station` → `ScaleRestServer`

Package layout by domain (not flat): `com.agent.pos.config`, `.printer`, `.scale`, `.dto`, `.network`. Main class in root `com.agent.pos`.

## Dependencies Whitelist

`com.sun.net.httpserver`, `jackson-databind` + `jackson-datatype-jsr310`, `slf4j-api`/`slf4j-simple`, `jSerialComm`. **No Spring Boot, no WebSocket, no additional frameworks.**

## Configuration Precedence

Environment Variables > `config.properties` file > Hardcoded defaults. Key env vars: `HTTP_PORT`, `PRINTER_PATH`, `PRINTER_NETWORK_IP`, `PRINTER_PORT`, `SCALE_PORT`, `SCALE_ENABLED`, `STATION_ID`, `SAAS_API_URL`, `AGENT_API_KEY`.

## Print Endpoint Contract

`POST /api/printer/print` **always returns HTTP 200** regardless of success or failure. `ticketId` echoes the sale folio: an 8-char hex String (e.g. `"5155558B"`). Errors are in the JSON body:
- Empty body → `200 {ticketId: "-1", success: false, error: "Body vacio"}`
- Unhandled exception → `200 {ticketId: "-1", success: false, error: "Error interno del servidor"}`
- Only non-POST methods get 405; OPTIONS gets 204.

## ESC/POS Formatting — MUST GET RIGHT

- **Ticket width: exactly 48 characters**
- **Detail line columns:** `CANT(5) PRODUCTO(24) PRECIO(8) TOTAL(8)` = 45 chars + 3 spaces = 48
- Product names > 24 chars wrap to continuation lines (split at word boundaries, hard-cut at 24)
- Text encoding: `ISO-8859-1` with PC850 charset command (`0x1B 0x74 0x02`)

### Ticket routing
- **Cash register ticket** (`deliveryOrderId == null`): opens cash drawer `0x1B 0x70 0x00 0x19 0xFA`, prints header/details/totals, cuts with feed `0x1D 0x56 0x42 0x03`
- **Delivery ticket** (`deliveryOrderId != null && deliveryAddress != null && !deliveryAddress.isEmpty`): **no cash drawer**, includes "PEDIDO A DOMICILIO" header, customer data section, "Atendio"/"Forma de Pago" fields, "PEDIDO #"

## Printer Transport & Fallback

- If `printerPath` matches IPv4 regex → network mode (`java.net.Socket` to `ip:printerPort`, 5s connect timeout)
- Otherwise → OS mode: Linux = raw `/dev/usb/lp*` device file, Windows = `javax.print`
- **Ethernet fallback:** When `printerNetworkIp` is configured and `printerPath` is not an IP, cable printing is tried first; on `PrinterException`, it auto-retries via Ethernet at `networkIp:printerPort`
- `isAvailable()` returns true if either cable or Ethernet path is reachable (when fallback is configured)

## Scale

- `TorreyScaleController` polls via `jSerialComm` at 9600 baud 8N1, command `0x57`, every 100ms
- 3 consecutive identical readings = stable; 5 consecutive read errors = auto-disconnect
- Weight endpoint returns cached reading; data >3s old marked unstable

## Network Binding

Server binds to `0.0.0.0` (not localhost) — required for LAN access from mobile devices running the React frontend.

## Heartbeat (Self-Discovery)

`HeartbeatTask` (`com.agent.pos.network`) reports the agent's IP to the SaaS backend every 5 minutes so the frontend can discover the agent dynamically.

- **Endpoint:** `PUT {SAAS_API_URL}/agent/stations/{STATION_ID}/url`
- **Payload:** `{"url":"http://{localIp}:{httpPort}","currentVersion":"{ApplicationMain.VERSION}"}`
- **Header:** `X-Agent-Key: {AGENT_API_KEY}`
- **IP Resolution:** Uses `DatagramSocket` connected to `8.8.8.8:10002` to force the OS routing table to resolve the real LAN IP (avoids `127.0.1.1` from `/etc/hosts` on Linux). Falls back to `InetAddress.getLocalHost().getHostAddress()` if no network is available.
- **Behaviour:** Runs immediately on startup then every 5 min via `ScheduledExecutorService` (daemon thread). If `STATION_ID` is blank/null, heartbeat is disabled with a WARN log. All failures are caught and logged — never crashes the agent.
- **Config:** `SAAS_API_URL` (default `http://localhost:8080/api/v1`), `AGENT_API_KEY` (default `default-agent-secret`), `STATION_ID` (default empty = disabled).

### Silent self-update
On HTTP **200**, the heartbeat parses the response body into the record `AgentUpdateResponse {updateAvailable, downloadUrl, sha256}` (Jackson, `@JsonIgnoreProperties(ignoreUnknown=true)`). If `updateAvailable` is true, a daemon thread `agent-updater` (guarded by an `AtomicBoolean` against overlap):
1. `HttpClient.send(..., BodyHandlers.ofFile(...))` streams `downloadUrl` → `pos-agent-next.jar` in the working dir.
2. Computes SHA-256 (`MessageDigest`, streamed) and compares case-insensitively to `sha256`.
3. **Match:** logs success and `System.exit(1)` — a **non-zero** code so WinSW's `onfailure restart` fires; the `run-agent.bat` wrapper then swaps `pos-agent-next.jar` → `pos-agent.jar` before relaunching the JVM.
4. **Mismatch / any error:** logs WARN, `Files.deleteIfExists(pos-agent-next.jar)`, old agent keeps running.

The Windows service runs via `run-agent.bat` (the WinSW `<executable>`), which performs the jar swap then `java -Xmx256m -jar pos-agent.jar`. `build-service-package.{sh,bat}` generate this wrapper into the dist.

### Release pipeline (producer side)
`.github/workflows/release.yml` fires on `v*` tag pushes: builds with `./mvnw clean package -DskipTests`, computes `sha256sum target/pos-agent.jar` → `target/sha256.txt`, and publishes a GitHub Release (`softprops/action-gh-release@v2`) with assets `pos-agent.jar` + `sha256.txt`. The SaaS feeds the jar asset URL as `downloadUrl` and the plain-hex `sha256.txt` as `sha256` in the heartbeat response — matching the agent's case-insensitive verification. Tag version and `ApplicationMain.VERSION` are bumped manually; keep them in sync.