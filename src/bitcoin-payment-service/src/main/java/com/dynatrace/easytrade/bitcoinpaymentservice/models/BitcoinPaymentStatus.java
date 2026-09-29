package com.dynatrace.easytrade.bitcoinpaymentservice.models;

import java.time.OffsetDateTime;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "A single status entry in a bitcoin payment's lifecycle.")
public record BitcoinPaymentStatus(
        String paymentId,
        Integer accountId,
        String status,
        OffsetDateTime timestamp,
        String details) {
}
