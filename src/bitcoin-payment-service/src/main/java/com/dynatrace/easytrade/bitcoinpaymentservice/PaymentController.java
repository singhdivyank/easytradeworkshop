package com.dynatrace.easytrade.bitcoinpaymentservice;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.dynatrace.easytrade.bitcoinpaymentservice.models.BitcoinPaymentRequest;
import com.dynatrace.easytrade.bitcoinpaymentservice.models.BitcoinPaymentResponse;
import com.dynatrace.easytrade.bitcoinpaymentservice.models.BitcoinPaymentStatus;
import com.dynatrace.easytrade.bitcoinpaymentservice.models.PaymentStatusType;
import com.dynatrace.easytrade.bitcoinpaymentservice.models.StandardResponse;

import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.OpenFeatureAPI;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;

/**
 * REST API for bitcoin payments.
 *
 * The accept endpoint is deliberately thin: it validates, writes a PENDING record, and
 * returns 202 immediately. Settlement (the slow, variable part) happens asynchronously in
 * {@link SettlementScheduler}. Clients poll the status endpoint. This shape is what lets the
 * request path stay fast and flat as traffic scales — the design goal for high volume.
 */
@RestController
@RequestMapping(value = "/v1/payments", produces = { "application/json", "application/xml" })
@CrossOrigin
@ApiResponses(value = {
        @ApiResponse(responseCode = "202", description = "Payment accepted for processing", content =
                @Content(schema = @Schema(implementation = StandardResponse.class))),
        @ApiResponse(responseCode = "400", description = "Bad request - check message and data for hints", content =
                @Content(schema = @Schema(implementation = StandardResponse.class))),
        @ApiResponse(responseCode = "404", description = "Payment not found", content =
                @Content(schema = @Schema(implementation = StandardResponse.class))),
        @ApiResponse(responseCode = "500", description = "Internal server error", content =
                @Content(schema = @Schema(implementation = StandardResponse.class))),
        @ApiResponse(responseCode = "503", description = "Feature disabled", content =
                @Content(schema = @Schema(implementation = StandardResponse.class))),
})
public class PaymentController {
    private static final Logger logger = LoggerFactory.getLogger(PaymentController.class);

    public static final String FEATURE_FLAG_KEY = "bitcoin_payments_enabled";
    public static final String PAYMENT_ACCEPTED = "Bitcoin payment accepted for processing.";
    public static final String FEATURE_DISABLED = "Bitcoin payments are currently disabled.";
    public static final String PAYMENT_NOT_FOUND = "No bitcoin payment found for the given id.";
    public static final String INVALID_REQUEST = "Invalid payment request.";

    private final DatabaseHelper dbHelper;
    private final OpenFeatureAPI openFeatureAPI;

    public PaymentController(DatabaseHelper dbHelper, OpenFeatureAPI openFeatureAPI) {
        this.dbHelper = dbHelper;
        this.openFeatureAPI = openFeatureAPI;
    }

    @GetMapping("/health")
    @Operation(summary = "Liveness/readiness probe")
    public ResponseEntity<StandardResponse> health() {
        return buildResponseEntity(HttpStatus.OK, "ok", null, null, null);
    }

    @PostMapping(value = "", consumes = { "application/json", "application/xml" })
    @Operation(summary = "Initiate a bitcoin payment (asynchronous)")
    public ResponseEntity<StandardResponse> createPayment(@RequestBody BitcoinPaymentRequest request) {
        // Feature gate — default false, and the flag client falls back to false if the flag
        // service is slow/unavailable, so the feature fails closed rather than stalling.
        if (!isFeatureEnabled()) {
            return buildResponseEntity(HttpStatus.SERVICE_UNAVAILABLE, FEATURE_DISABLED, null, null, null);
        }

        Optional<String> validationError = validate(request);
        if (validationError.isPresent()) {
            return buildResponseEntity(HttpStatus.BAD_REQUEST, INVALID_REQUEST, null, validationError.get(), null);
        }

        try {
            String paymentId = dbHelper.insertNewPayment(request);
            logger.info("Accepted bitcoin payment {} for account {}", paymentId, request.accountId());
            return buildResponseEntity(HttpStatus.ACCEPTED, PAYMENT_ACCEPTED,
                    new BitcoinPaymentResponse(paymentId, PaymentStatusType.PENDING.getType()), null, null);
        } catch (SQLException e) {
            return handleSQLException(e);
        }
    }

    @GetMapping("/{paymentId}/status")
    @Operation(summary = "Get the latest status of a bitcoin payment")
    public ResponseEntity<StandardResponse> getStatus(@PathVariable String paymentId) {
        try {
            Optional<BitcoinPaymentStatus> status = dbHelper.getLatestStatus(paymentId);
            return status
                    .map(s -> buildResponseEntity(HttpStatus.OK, "Status found successfully.", s, null, null))
                    .orElse(buildResponseEntity(HttpStatus.NOT_FOUND, PAYMENT_NOT_FOUND, null, null, null));
        } catch (SQLException e) {
            return handleSQLException(e);
        }
    }

    private boolean isFeatureEnabled() {
        try {
            final Client client = openFeatureAPI.getClient();
            return client.getBooleanValue(FEATURE_FLAG_KEY, false);
        } catch (Exception e) {
            logger.warn("Feature flag evaluation failed, defaulting to disabled: {}", e.getMessage());
            return false;
        }
    }

    private Optional<String> validate(BitcoinPaymentRequest request) {
        if (request == null) {
            return Optional.of("Request body is required.");
        }
        if (request.accountId() == null) {
            return Optional.of("accountId is required.");
        }
        if (request.amount() == null || request.amount().compareTo(BigDecimal.ZERO) <= 0) {
            return Optional.of("amount must be greater than zero.");
        }
        if (request.currency() == null || request.currency().isBlank()) {
            return Optional.of("currency is required.");
        }
        if (request.walletAddress() == null || request.walletAddress().isBlank()) {
            return Optional.of("walletAddress is required.");
        }
        return Optional.empty();
    }

    private ResponseEntity<StandardResponse> buildResponseEntity(HttpStatus status, String message, Object results,
            Object data, Object error) {
        if (status.is5xxServerError()) {
            logger.error(message);
        } else {
            logger.info(message);
        }
        return ResponseEntity
                .status(status)
                .body(new StandardResponse(status.value(), message, results, data, error));
    }

    private ResponseEntity<StandardResponse> handleSQLException(SQLException e) {
        return buildResponseEntity(HttpStatus.INTERNAL_SERVER_ERROR, "An exception occurred!", null, null,
                e.getMessage());
    }
}
