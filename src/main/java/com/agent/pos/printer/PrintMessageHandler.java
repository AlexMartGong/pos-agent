package com.agent.pos.printer;

import com.agent.pos.dto.TicketDTO;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class PrintMessageHandler {

    private static final DateTimeFormatter LOG_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ESCPOSPrinter printer;
    private final ObjectMapper objectMapper;

    public PrintMessageHandler(ESCPOSPrinter printer, ObjectMapper objectMapper) {
        this.printer = printer;
        this.objectMapper = objectMapper;
    }

    public PrintResult handle(String message) {
        log("INFO", "Mensaje recibido del servidor (" + message.length() + " bytes)");

        try {
            TicketDTO ticket = objectMapper.readValue(message, TicketDTO.class);
            log("INFO", "Ticket #" + ticket.getId() + " parseado - Cliente: " + ticket.getCustomerName());

            return printTicket(ticket);

        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            log("ERROR", "Error parseando JSON: " + e.getMessage());
            log("DEBUG", "Mensaje recibido: " + message.substring(0, Math.min(200, message.length())));
            return new PrintResult(-1, false, "Error parseando JSON: " + e.getMessage());
        } catch (Exception e) {
            log("ERROR", "Error procesando mensaje: " + e.getMessage());
            e.fillInStackTrace();
            return new PrintResult(-1, false, "Error procesando mensaje: " + e.getMessage());
        }
    }

    private PrintResult printTicket(TicketDTO ticket) {
        boolean success = false;
        String errorMessage = null;

        try {
            if (printer.isAvailable()) {
                log("INFO", "Imprimiendo ticket #" + ticket.getId() + "...");
                printer.print(ticket);
                success = true;
                log("INFO", "Ticket #" + ticket.getId() + " impreso correctamente");
            } else {
                errorMessage = "Impresora no disponible o desconectada";
                log("ERROR", errorMessage);
            }
        } catch (IOException e) {
            errorMessage = "Error de I/O al imprimir: " + e.getMessage();
            log("ERROR", errorMessage);
        } catch (ESCPOSPrinter.PrinterException e) {
            errorMessage = "Error de impresora: " + e.getMessage();
            log("ERROR", errorMessage);
        } catch (Exception e) {
            errorMessage = "Error inesperado al imprimir: " + e.getMessage();
            log("ERROR", errorMessage);
            e.fillInStackTrace();
        }

        return new PrintResult(ticket.getId(), success, errorMessage);
    }

    private static void log(String level, String message) {
        String timestamp = LocalDateTime.now().format(LOG_FORMAT);
        System.out.println(timestamp + " [" + level + "] " + message);
    }
}