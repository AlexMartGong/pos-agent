package com.agent.pos.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TicketDTO(
        Long id,
        Long employeeId,
        String employeeName,
        String employeePhone,
        Long deliveryOrderId,
        Long customerId,
        String customerName,
        String customerPhone,
        BigDecimal customerDiscount,
        String deliveryAddress,
        Long paymentMethodId,
        String paymentMethodName,
        LocalDateTime datetime,
        BigDecimal subtotal,
        BigDecimal discountAmount,
        BigDecimal total,
        BigDecimal amountTendered,
        BigDecimal changeDue,
        boolean paid,
        String notes,
        List<SaleDetailDTO> saleDetails,
        String businessName,
        String businessAddress,
        String businessPhone
) {}