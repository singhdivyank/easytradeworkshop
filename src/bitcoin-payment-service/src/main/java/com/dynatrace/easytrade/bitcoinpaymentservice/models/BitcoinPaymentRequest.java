package com.dynatrace.easytrade.bitcoinpaymentservice.models;

import java.math.BigDecimal;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Request to initiate a bitcoin payment.")
public record BitcoinPaymentRequest(
        @Schema(description = "EasyTrade account initiating the payment.")
        Integer accountId,
        @Schema(description = "Amount to pay, denominated in the fiat currency below.")
        BigDecimal amount,
        @Schema(description = "Fiat currency of the amount, ISO 4217 (e.g. USD, EUR).")
        String currency,
        @Schema(description = "Destination bitcoin wallet address.")
        String walletAddress) {
}
