package com.dynatrace.easytrade.bitcoinpaymentservice.models;

/**
 * Lifecycle of a bitcoin payment. Kept as an append-only status history so the flow can be
 * replayed and audited, mirroring the credit-card order status pattern.
 *
 * pending    -> accepted, not yet submitted to the (simulated) network
 * confirming -> broadcast, waiting for network confirmations
 * confirmed  -> settled successfully (terminal)
 * failed     -> settlement failed (terminal)
 */
public enum PaymentStatusType {
    PENDING("pending", 0),
    CONFIRMING("confirming", 1),
    CONFIRMED("confirmed", 2),
    FAILED("failed", 2);

    private final String type;
    private final int sequence;

    PaymentStatusType(String type, int sequence) {
        this.type = type;
        this.sequence = sequence;
    }

    public String getType() {
        return type;
    }

    public int getSequence() {
        return sequence;
    }

    public boolean isTerminal() {
        return this == CONFIRMED || this == FAILED;
    }

    public static PaymentStatusType fromType(String value) {
        for (PaymentStatusType t : values()) {
            if (t.type.equalsIgnoreCase(value)) {
                return t;
            }
        }
        throw new IllegalArgumentException("Unknown payment status: " + value);
    }
}
