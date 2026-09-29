package com.dynatrace.easytrade.bitcoinpaymentservice.models;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Result of accepting a bitcoin payment for asynchronous processing.")
public record BitcoinPaymentResponse(
        @Schema(description = "Server-generated payment id (UUID). Use it to poll status.")
        String paymentId,
        @Schema(description = "Current status of the payment at time of acceptance.")
        String status) {
}
