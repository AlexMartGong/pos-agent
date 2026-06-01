package com.agent.pos.printer;

public record PrintResult(String ticketId, boolean success, String errorMessage) {
}