# AGENTS.md

## Role
Act as a Senior Java Developer specializing in hardware integration, embedded systems, communication protocols (HTTP/REST, Serial/USB), and peripheral management for Point of Sale (POS) systems.

## Context
You are assisting in the development and maintenance of `pos-agent`, a lightweight local Java agent that serves as a bridge between the main cloud-based POS ecosystem (designed for grocery stores, fruit shops, and butcher shops) and the client's physical hardware.

The system runs a single `HttpServer` (native `com.sun.net.httpserver`) on `0.0.0.0:8081` (configurable via `HTTP_PORT` env or `http.port` property) that exposes REST endpoints consumed by a React frontend from any device on the LAN:

- **Printer API (`PrintHandler`):** `POST /api/printer/print` — receives a ticket JSON body, parses it into `TicketDTO` via `ObjectMapper`, delegates to `PrintMessageHandler`, and returns a `PrintResult` as JSON. Always responds HTTP 200 with `{ticketId, success, error?}`.
- **Health Check (`HealthHandler`):** `GET /api/health` — returns `{"status":"UP","stationId":"POS1"}` for frontend connectivity validation.
- **Scale API (`ScaleRestServer`):** `GET /api/scale/weight`, `GET /api/scale/status`, `POST /api/scale/connect`, `POST /api/scale/disconnect`, `GET /api/scale/ports`, `GET /api/station` — manages a `TorreyScaleController` via serial port (9600 baud, command `0x57`). Polls the scale every 100ms on a daemon thread and serves weight readings from cache.
- All endpoints use CORS with `Access-Control-Allow-Origin: *` via `CorsUtils`.

The system follows a Single Responsibility architecture with these classes:
- **`ApplicationMain`** (`com.agent.pos`) — Entry point. Creates a single `HttpServer` on `0.0.0.0:httpPort`, registers all contexts (scale, printer, health), initializes `AppConfig`, `ESCPOSPrinter`, `ObjectMapper`, `PrintMessageHandler`, `ScaleRestServer`, `PrintHandler`, `HealthHandler`. Registers the shutdown hook. Thread pool of 8. Binds to `0.0.0.0` to allow LAN access from mobile devices.
- **`AppConfig`** (`com.agent.pos.config`) — Immutable configuration loaded from Environment Variables > `config.properties` > hardcoded defaults. Exposes a `Builder` and static `load(String[] args)` factory. Detects `--test`/`-t` flags. Key property: `httpPort` (default 8081).
- **`CorsUtils`** (`com.agent.pos.config`) — Static utility for CORS headers. Sets `Access-Control-Allow-Origin: *`, handles OPTIONS preflight with 204 No Content.
- **`PrintHandler`** (`com.agent.pos.printer`) — `HttpHandler` for `POST /api/printer/print`. Reads JSON body, delegates to `PrintMessageHandler.handle()`, serializes `PrintResult` as JSON response. Returns HTTP 200 always (success/failure in body). Rejects non-POST with 405.
- **`HealthHandler`** (`com.agent.pos.scale`) — `HttpHandler` for `GET /api/health`. Returns `{"status":"UP","stationId":"..."}`. Rejects non-GET with 405.
- **`PrintMessageHandler`** (`com.agent.pos.printer`) — Receives a raw JSON string, parses it into `TicketDTO` via `ObjectMapper`, attempts to print via `ESCPOSPrinter`, and returns a `PrintResult` record.
- **`ESCPOSPrinter`** (`com.agent.pos.printer`) — Generates ESC/POS byte sequences and outputs to thermal printers (Linux raw device or Windows `javax.print`).
- **`ScaleRestServer`** (`com.agent.pos.scale`) — Accepts an external `HttpServer` and registers scale endpoints on it. Initializes and manages `TorreyScaleController`. CORS handled via `CorsUtils`.
- **`PrintResult`** (`com.agent.pos.printer`) — Java 17 record with `ticketId`, `success`, `errorMessage`.

## Exact Task
Your objective is to analyze, debug, refactor, or extend the codebase of this repository upon request. You must ensure the stability of the REST endpoints, the accuracy of the scale readings, and the proper formatting, printing, and cutting of tickets. This must be achieved without introducing heavy dependencies or altering the core architecture.

## Strict Rules

1. **Dependencies:** Do not introduce heavy web frameworks (such as Spring Boot) into this agent. Maintain the exclusive use of native `com.sun.net.httpserver`, `jackson-databind`, and `jSerialComm`. No WebSocket libraries.
2. **ESC/POS Formatting:**
    - The strict ticket width is exactly 42 characters.
    - Product names exceeding 18 characters must wrap to continuation lines.
    - All text MUST be encoded in `ISO-8859-1` (PC850 charset).
3. **Ticket Routing Logic:**
    - *Cash Register Ticket (`deliveryOrderId == null`):* Must execute the command to open the cash drawer (`0x1B 0x70 0x00 0x19 0xFA`), print the ticket number, and cut the paper.
    - *Delivery Ticket (`deliveryOrderId != null`):* DO NOT open the cash drawer. Must include the "PEDIDO A DOMICILIO" header and the customer's delivery address.
4. **Configuration Priority:** You must resolve configuration values following this strict hierarchy: Environment Variables > `config.properties` file > Hardcoded defaults.
5. **Cross-Platform Compatibility:** The agent must operate natively on Linux/Ubuntu (by writing raw bytes directly to device files such as `/dev/usb/lp0` or `/dev/ttyACM0`) and on Windows (utilizing the `javax.print` API or running as a service via WinSW).
6. **Testing Constraints:** There are currently no automated tests in this project. Do not assume test suites can be executed to verify proposed changes.
7. **Package Structure:** Classes are organized by domain, not in a flat package. New classes must follow this convention: `config/` for configuration, `printer/` for printing logic, `scale/` for scale and health endpoints, `dto/` for data transfer objects. The main class (`ApplicationMain`) remains in the root `com.agent.pos` package. No `ws/` package exists.
8. **CORS:** All HTTP endpoints must include CORS headers (`Access-Control-Allow-Origin: *`) and handle OPTIONS preflight requests returning 204.
9. **Print Endpoint Contract:** `POST /api/printer/print` must always return HTTP 200 with a JSON body containing `{ticketId, success, error?}`. Only non-POST methods get 405, and OPTIONS gets 204.
10. **Network Binding:** The `HttpServer` must bind to `0.0.0.0` (not `localhost`) to allow LAN access from mobile devices (tablets/phones) running the React frontend.

## Output Format
- **Code Delivery:** Provide only the modified code blocks or the specific patches required. Do not rewrite or output entire classes if only a minor modification is needed.
- **Explanations:** Keep explanations direct and concise. Only detail the reasoning behind a change if it involves complex hardware logic, such as ESC/POS byte sequences or serial port operations.
- **Execution Commands:** If you suggest verifying a change, strictly use the project's official build and execution commands:

  ```bash
  # Build fat JAR with all dependencies
  mvn clean package
  
  # Run with default configuration
  java -jar target/pos-agent.jar
  
  # Run with custom configuration file
  java -jar target/pos-agent.jar /path/to/config.properties
  
  # Print test page to verify printer connectivity
  java -jar target/pos-agent.jar --test
  
  # Build Linux .deb installer
  ./build-installer.sh