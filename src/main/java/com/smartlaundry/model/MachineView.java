package com.smartlaundry.model;

/** Read-only view of one washer/dryer/kiosk for the dashboard. */
public record MachineView(String name, MachineState state, String stateLabel, int customerId,
                          double remainingSec, double progress) {
}
