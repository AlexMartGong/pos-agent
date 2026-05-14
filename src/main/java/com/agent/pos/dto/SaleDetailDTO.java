package com.agent.pos.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SaleDetailDTO(
        Long detailId,
        Long saleId,
        LocalDateTime saleDate,
        Long productId,
        String productName,
        String productCategory,
        BigDecimal quantity,
        BigDecimal unitPrice,
        BigDecimal discount,
        BigDecimal subtotal,
        BigDecimal total
) {}