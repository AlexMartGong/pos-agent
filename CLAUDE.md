# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Role
Senior Java developer for hardware integration on POS systems: HTTP/REST, Serial/USB, network/Ethernet, thermal printers, electronic scales.

## What this is
`pos-agent` is a small local Java agent that bridges the cloud POS (grocery/fruit/butcher shops) and the client's physical hardware. Runs on the same LAN as the React frontend. No web framework — only native `com.sun.net.httpserver` + `java.net.http.HttpClient`.

## Build & Run

```bash
mvn clean package                                  # Fat JAR → target/pos-agent.jar (./mvnw also works)
java -jar target/pos-agent.jar                     # Run agent
java -jar target/pos-agent.jar --test              # Print test page, then exit (-t also works)
java -jar target/pos-agent.jar /path/to/config.properties
./build-installer.sh                               # Linux .deb via jpackage (JDK 14+)
build-installer.bat                                # Windows installer (jpackage); build-service-package.sh/.bat = WinSW bundle
```

Requires **Java 17** (pom `maven.compiler.target=17`). `HttpClient`/`HttpServer` are native Java 11+.
Main class: `com.agent.pos.ApplicationMain`. JAR finalName `pos-agent` (no assembly id).
**No automated tests exist.** `mvn test` verifies nothing. Validate changes by running the jar.

## Architecture (big picture)

Single `HttpServer` on `0.0.0.0:8081` (bind to `0.0.0.0` is required — mobile devices on LAN consume the API). Thread pool of 8. One `HttpServer` shared across all handler classes.

Package layout (domain, not flat):
- `com.agent.pos` — `ApplicationMain` (wiring, lifecycle, shutdown hook, heartbeat scheduler)
- `com.agent.pos.config` — `AppConfig` (immutable, builder, `load(args)` factory), `CorsUtils`
- `com.agent.pos.printer` — `PrintHandler`, `PrintMessageHandler`, `ESCPOSPrinter`, `PrintResult`
- `com.agent.pos.scale` — `ScaleRestServer`, `TorreyScaleController`, `HealthHandler`
- `com.agent.pos.dto` — `TicketDTO`, `SaleDetailDTO` (Jackson records, `@JsonIgnoreProperties(ignoreUnknown=true)`)
- `com.agent.pos.network` — `HeartbeatTask` (SaaS self-discovery)

Endpoints (all CORS-wrapped via `CorsUtils`):
- `POST /api/printer/print` → `PrintHandler` → `PrintMessageHandler` → `ESCPOSPrinter`
- `GET /api/health` → `HealthHandler` (returns `{"status":"UP","stationId":...}`)
- `GET /api/scale/weight`, `GET /api/scale/status`, `POST /api/scale/connect`, `POST /api/scale/disconnect`, `GET /api/scale/ports`, `GET /api/station` → `ScaleRestServer`

## Strict rules

1. **Dependencies allowed:** `com.sun.net.httpserver`, `java.net.http.HttpClient` (Java 11+ native), `jackson-databind` + `jackson-datatype-jsr310`, `slf4j-api`/`slf4j-simple`, `jSerialComm`. **No Spring Boot, no WebSocket libs, no other frameworks.**

2. **ESC/POS formatting (MUST get right):**
   - Ticket width: **exactly 48 chars**.
   - Detail columns: `CANT(5) PRODUCTO(24) PRECIO(8) TOTAL(8)` = 45 + 3 spaces = 48.
   - Product names > 24 chars wrap to continuation lines.
   - Text encoding: `ISO-8859-1` with PC850 charset command (`0x1B 0x74 0x02`).

3. **Ticket routing:**
   - **Cash register** (`deliveryOrderId == null`): open drawer `0x1B 0x70 0x00 0x19 0xFA`, print header/details/totals, cut + feed `0x1D 0x56 0x42 0x03`.
   - **Delivery** (`deliveryOrderId != null && deliveryAddress != null && !deliveryAddress.isEmpty()`): **no drawer**, `PEDIDO A DOMICILIO` header, customer block (name/phone/discount/address), `Atendio` + `Forma de Pago` fields, `PEDIDO #`.

4. **Configuration precedence:** Env vars > `config.properties` > hardcoded defaults. Loaded by `AppConfig.load(args)`. Key env vars: `HTTP_PORT`, `STATION_ID`, `PRINTER_PATH`, `PRINTER_NAME` (prop `printer.name`, Windows `javax.print` target), `PRINTER_NETWORK_IP`, `PRINTER_PORT`, `SCALE_PORT`, `SCALE_ENABLED`, `SCALE_AUTO_CONNECT`, `SAAS_API_URL`, `AGENT_API_KEY`, `BUSINESS_NAME`/`BUSINESS_ADDRESS`/`BUSINESS_PHONE`.

5. **Printer transport & fallback:**
   - If `printerPath` matches IPv4 regex (each octet 0–255) → direct network mode via `java.net.Socket` to `path:printerPort` (5s connect, 2s for `isAvailable()`).
   - Else → OS mode: Linux raw device file (`/dev/usb/lp*`), Windows `javax.print`.
   - If `printerNetworkIp` is set AND `printerPath` is not an IP: cable is tried first; on `PrinterException`, agent logs `[WARN]` and retries via Ethernet at `networkIp:printerPort`. Applies to both `print()` and `printTestPage()`. `isAvailable()` returns true if either path is reachable.
   - When `printerPath` is already an IPv4, no fallback (already on Ethernet).

6. **Print endpoint contract:** `POST /api/printer/print` **always returns HTTP 200** with `{ticketId, success, error?}`. `id` is the sale folio: an 8-char hex String (e.g. `"5155558B"`), echoed back as `ticketId`. Empty body → `{ticketId:"-1", success:false, error:"Body vacio"}`. Unhandled exception → `{ticketId:"-1", success:false, error:"Error interno del servidor"}`. Only non-POST gets 405; OPTIONS gets 204.

7. **CORS (every endpoint):** `Access-Control-Allow-Origin: *`, `Allow-Methods: GET, POST, OPTIONS`, `Allow-Headers: Content-Type, Authorization, Accept, Origin`. OPTIONS preflight → 204 with `Max-Age: 86400`.

8. **Scale:** `TorreyScaleController` polls via `jSerialComm` (9600 8N1, command `0x57`) every 100ms on daemon thread `scale-polling`. 3 consecutive identical readings = stable. 5 consecutive read errors = auto-disconnect, polling stops. `/api/scale/weight` returns 503 if disabled/disconnected, 500 on read error, 200 on success. Cached readings older than 3s are marked unstable.

9. **Heartbeat (self-discovery + self-update):** `HeartbeatTask` (`com.agent.pos.network`) reports the agent's URL to the SaaS every 5 min so the frontend can discover it dynamically.
   - `PUT {SAAS_API_URL}/agent/stations/{STATION_ID}/url` with body `{"url":"http://{localIp}:{httpPort}","currentVersion":"{ApplicationMain.VERSION}"}` and header `X-Agent-Key: {AGENT_API_KEY}`. `VERSION` is `public static final String` on `ApplicationMain` (currently `"1.0.0"`).
   - LAN IP resolved via `DatagramSocket` connected to `8.8.8.8:10002` (no real traffic sent) to force the OS routing table to return the real interface IP — avoids `127.0.1.1` that `InetAddress.getLocalHost()` returns on Linux when `/etc/hosts` maps the hostname to loopback. Falls back to `InetAddress.getLocalHost().getHostAddress()` offline.
   - Runs once at startup, then every 5 min via `ScheduledExecutorService` (daemon). 5s connect + 5s request timeout. All failures caught and logged — never crashes the agent.
   - If `STATION_ID` is blank/null, heartbeat is **disabled** with a WARN log.
   - **Self-update:** on HTTP 200 the body is parsed into record `AgentUpdateResponse {updateAvailable, downloadUrl, sha256}` (Jackson, ignore-unknown). If `updateAvailable`, a daemon thread `agent-updater` (guarded by `AtomicBoolean`) streams `downloadUrl` → `pos-agent-next.jar` (working dir) via `BodyHandlers.ofFile`, computes SHA-256 (`MessageDigest`), and compares case-insensitively. **Match** → log + `System.exit(1)` (non-zero so WinSW `onfailure restart` fires). **Mismatch/error** → WARN + `Files.deleteIfExists(pos-agent-next.jar)`, old agent stays up. Every step try-caught; never breaks the heartbeat.
   - **Update wrapper:** WinSW `<executable>` is `run-agent.bat` (generated by `build-service-package.{sh,bat}` into the dist). On each start it swaps `pos-agent-next.jar` → `pos-agent.jar` (if present), then runs `java -Xmx256m -jar pos-agent.jar`. `service/pos-printer-agent.xml` uses `<onfailure action="restart" delay="5 sec"/>` + `<resetfailure>10 sec</resetfailure>`. The non-zero exit from a verified update is what triggers WinSW to relaunch the wrapper and apply the swap.
   - **Release pipeline (producer):** `.github/workflows/release.yml` fires on `v*` tag pushes — builds with `./mvnw clean package -DskipTests`, writes `sha256sum target/pos-agent.jar` → `target/sha256.txt`, and publishes a GitHub Release (`softprops/action-gh-release@v2`) with assets `pos-agent.jar` + `sha256.txt`. The SaaS hands the jar asset URL back as `downloadUrl` and the plain-hex `sha256.txt` as `sha256` in the heartbeat response. The tag (`v1.0.0`) and `ApplicationMain.VERSION` are bumped by hand — keep them in sync.

10. **Cross-platform:** Linux/Ubuntu (raw `/dev/usb/lp*`, `/dev/ttyACM*`), Windows (`javax.print` or WinSW service via `service/`), Ethernet (`java.net.Socket` to port 9100 or custom `printerPort`).

11. **Test mode:** `--test`/`-t` constructs `ESCPOSPrinter`, calls `printTestPage()` (opens drawer, prints `PRINT TEST` + OS/printer-path info, cuts with feed), then exits.

## Sibling docs

- **`AGENTS.md`** — near-duplicate of this file (same architecture, endpoint contract, ESC/POS rules) for non-Claude agents. When you change architecture/contract here, mirror it there (and vice versa) so they don't drift.

## Stale files — do NOT trust

- **`README.md`** — was rewritten in this commit, but historically described a removed WebSocket architecture. If you see WebSocket talk reappear, that's regression.
- **`build-installer.sh`** — was patched here to use the current `pos-agent.jar` + `com.agent.pos.ApplicationMain`. Earlier history referenced dead class `com.pasadita.pos.POSPrinterAgent` and dead JAR `pos-printer-agent.jar`. If `mvn` produces a different JAR name, this script needs an update.
- **`service/pos-printer-agent.xml`** — WinSW service descriptor for Windows. `<executable>` is now `run-agent.bat` (the self-update wrapper), which in turn runs `pos-agent.jar`. Old name lives on in the file name itself; keep in sync if renaming.

## Output expectations

- **Patches over rewrites.** Show the diff or block, not the whole class, unless the change is large.
- **Explain only non-obvious bytes/serial logic.** Don't narrate routine code.
- **Code/comments in Spanish are fine** where the existing codebase uses Spanish (`"Body vacio"`, `PEDIDO A DOMICILIO`, etc.).
