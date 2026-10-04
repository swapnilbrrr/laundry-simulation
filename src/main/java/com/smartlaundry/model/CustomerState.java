package com.smartlaundry.model;

/** Lifecycle stages of a customer. The order of the constants mirrors the mandatory workflow. */
public enum CustomerState {
    ARRIVED("Arrival"),
    WAITING_WASHER("Washer Queue"),
    WASHING("Washing"),
    WAITING_DRYER("Dryer Queue"),
    DRYING("Drying"),
    WAITING_PAYMENT("Payment Queue"),
    PAYMENT_RETRY_WAIT("Payment Retry Wait"),
    PAYING("Payment"),
    COMPLETED("Completed"),
    CANCELLED("Cancelled");

    private final String label;

    CustomerState(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
