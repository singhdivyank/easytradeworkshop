package com.dynatrace.easytrade.bitcoinpaymentservice;

import java.sql.Connection;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.dynatrace.easytrade.bitcoinpaymentservice.models.PaymentStatusType;

/**
 * Drives bitcoin payments through their lifecycle out of band from the request path:
 *
 *   PENDING    -> CONFIRMING   (payment "broadcast" to the network)
 *   CONFIRMING -> CONFIRMED    (enough network confirmations reached)
 *
 * This mirrors credit-card-order-service's WorkScheduler poll pattern (poll the DB for work,
 * advance status), which lets the accept endpoint return 202 immediately while settlement —
 * inherently slow and variable for a blockchain — happens asynchronously. That decoupling is
 * what keeps request latency flat as traffic scales.
 */
@Service
public class SettlementScheduler extends BaseScheduler {
    private static final Logger logger = LoggerFactory.getLogger(SettlementScheduler.class);

    private static final int DEFAULT_DELAY_SECONDS = 5;
    private static final int DEFAULT_RATE_SECONDS = 10;
    private static final int DEFAULT_BATCH_LIMIT = 500;

    private final DatabaseHelper dbHelper;
    private final int batchLimit;

    public SettlementScheduler(DatabaseHelper dbHelper) {
        super("settlement",
                readIntEnv("SETTLEMENT_DELAY", DEFAULT_DELAY_SECONDS),
                readIntEnv("SETTLEMENT_RATE", DEFAULT_RATE_SECONDS));
        this.dbHelper = dbHelper;
        this.batchLimit = readIntEnv("SETTLEMENT_BATCH_LIMIT", DEFAULT_BATCH_LIMIT);
    }

    @Override
    protected void run() {
        logger.debug("Running SettlementScheduler task");
        try (Connection conn = dbHelper.getConnection()) {
            // Advance CONFIRMING payments to a terminal state first, then admit new PENDING ones,
            // so a payment does not skip the CONFIRMING phase within a single tick.
            advance(conn, PaymentStatusType.CONFIRMING, PaymentStatusType.CONFIRMED,
                    "network confirmations reached");
            advance(conn, PaymentStatusType.PENDING, PaymentStatusType.CONFIRMING,
                    "broadcast to bitcoin network");
        } catch (Exception e) {
            logger.error("Caught exception while running SettlementScheduler task: {}", e.getMessage(), e);
        }
        logger.debug("Finished SettlementScheduler task");
    }

    private void advance(Connection conn, PaymentStatusType from, PaymentStatusType to, String details) {
        try {
            List<String> ids = dbHelper.getPaymentIdsInStatus(conn, from);
            int processed = 0;
            for (String paymentId : ids) {
                if (processed >= batchLimit) {
                    logger.info("Reached settlement batch limit {} for transition {} -> {}; deferring rest",
                            batchLimit, from.getType(), to.getType());
                    break;
                }
                dbHelper.insertStatus(conn, paymentId, to, details);
                processed++;
            }
            if (processed > 0) {
                logger.info("Advanced {} payment(s) from {} to {}", processed, from.getType(), to.getType());
            }
        } catch (Exception e) {
            logger.error("Failed advancing payments from {} to {}: {}", from.getType(), to.getType(), e.getMessage(),
                    e);
        }
    }

    private static int readIntEnv(String name, int fallback) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            logger.warn("Invalid value '{}' for {}, using default {}", value, name, fallback);
            return fallback;
        }
    }
}
