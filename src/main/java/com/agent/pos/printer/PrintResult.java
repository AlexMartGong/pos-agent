package com.agent.pos.printer;

public record PrintResult(long ticketId, boolean success, String errorMessage) {
}