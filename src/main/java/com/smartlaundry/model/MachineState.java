package com.smartlaundry.model;

public enum MachineState {
    AVAILABLE("Available"),
    IN_USE("In Use"),
    FAILED("Failed"),
    RECOVERING("Recovering"),
    OFFLINE("Offline");

    private final String label;

    MachineState(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
