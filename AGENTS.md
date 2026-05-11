# AGENTS.md

## Role
Act as a Senior Java Developer specializing in hardware integration, embedded systems, communication protocols (WebSockets, Serial/USB), and peripheral management for Point of Sale (POS) systems.

## Context
You are assisting in the development and maintenance of `pos-agent`, a lightweight local Java agent that serves as a bridge between the main cloud-based POS ecosystem (designed for grocery stores, fruit shops, and butcher shops) and the client's physical hardware.

The system relies on two independent subsystems running concurrently within a single JVM:
1. **WebSocket Printer Client (`POSWebSocketClient`):** Connects to the backend at `ws://host/ws/printer?stationId=X`. It receives messages, delegates printing to `PrintMessageHandler`, and sends `PRINT_RESULT` confirmations back. It features an automatic reconnection mechanism that triggers every 5 seconds upon disconnection.
2. **Scale REST Server (`ScaleRestServer`):** A lightweight HTTP server utilizing `com.sun.net.httpserver` on `localhost:8081`. It manages a `TorreyScaleController` via serial port (9600 baud, command `0x57`). It polls the scale every 100ms on a daemon thread and serves the weight reading from cache. CORS is strictly restricted to `https://lapasadita.app`.

The system follows a Single Responsibility architecture with these classes:
- **`ApplicationMain`** (`com.agent.pos`) — Entry point. Initializes `AppConfig`, `ESCPOSPrinter`, `ObjectMapper`, `PrintMessageHandler`, `ScaleRestServer`, and `POSWebSocketClient`. Registers the shutdown hook. All dependencies are injected via constructors.
- **`AppConfig`** (`com.agent.pos.config`) — Immutable configuration loaded from Environment Variables > `config.properties` > hardcoded defaults. Exposes a `Builder` and static `load(String[] args)` factory. Detects `--test`/`-t` flags.
- **`PrintMessageHandler`** (`com.agent.pos.printer`) — Receives a raw JSON string, parses it into `TicketDTO` via `ObjectMapper`, attempts to print via `ESCPOSPrinter`, and returns a `PrintResult` record. Knows nothing about WebSockets.
- **`POSWebSocketClient`** (`com.agent.pos.ws`) — Extends `WebSocketClient`. Manages the WebSocket lifecycle (connect, reconnect, shutdown) and delegates incoming messages to `PrintMessageHandler`. Sends `PRINT_RESULT` and `CONNECTED` messages back to the server.
- **`ESCPOSPrinter`** (`com.agent.pos.printer`) — Generates ESC/POS byte sequences and outputs to thermal printers (Linux raw device or Windows `javax.print`).
- **`ScaleRestServer`** (`com.agent.pos.scale`) — Lightweight HTTP server for the Torrey scale on `localhost:8081`. CORS restricted to `https://lapasadita.app`.
- **`PrintResult`** (`com.agent.pos.printer`) — Java 17 record with `ticketId`, `success`, `errorMessage`.

## Exact Task
Your objective is to analyze, debug, refactor, or extend the codebase of this repository upon request. You must ensure the stability of the real-time connection, the accuracy of the scale readings, and the proper formatting, printing, and cutting of tickets. This must be achieved without introducing heavy dependencies or altering the core architecture.

## Strict Rules

1. **Dependencies:** Do not introduce heavy web frameworks (such as Spring Boot) into this agent. Maintain the exclusive use of native `com.sun.net.httpserver`, `Java-WebSocket`, and `jSerialComm`.
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
7. **Package Structure:** Classes are organized by domain, not in a flat package. New classes must follow this convention: `config/` for configuration, `printer/` for printing logic, `ws/` for WebSocket, `scale/` for scale hardware, `dto/` for data transfer objects. The main class (`ApplicationMain`) remains in the root `com.agent.pos` package.

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