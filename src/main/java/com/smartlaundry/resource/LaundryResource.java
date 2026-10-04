package com.smartlaundry.resource;

import com.smartlaundry.model.MachineState;
import com.smartlaundry.model.MachineView;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * One physical machine (washer, dryer or kiosk).
 *
 * The pool controls which customer receives the resource. The atomic owner field
 * provides a second ownership check, while the synchronized state methods keep
 * the machine's display fields consistent.
 */
public abstract class LaundryResource {
    private final String type;
    private final String name;
    private final AtomicInteger ownerId = new AtomicInteger(0);

    private MachineState state = MachineState.AVAILABLE;
    private int customerId;
    private long startNanos;
    private long durationMs;

    protected LaundryResource(String type, int index) {
        this.type = type;
        this.name = String.format("%s-%02d", type, index);
    }

    public String getName() {
        return name;
    }

    public String getType() {
        return type;
    }

    /** Atomically take ownership; false means another customer already owns the resource. */
    public boolean claim(int customerId) {
        return ownerId.compareAndSet(0, customerId);
    }

    public int currentOwner() {
        return ownerId.get();
    }

    public synchronized void beginUse(int customerId, long durationMs, long simNanos) {
        this.state = MachineState.IN_USE;
        this.customerId = customerId;
        this.durationMs = durationMs;
        this.startNanos = simNanos;
    }

    public synchronized void setState(MachineState newState) {
        this.state = newState;
    }

    public synchronized void markFailed() {
        ownerId.set(0);
        this.state = MachineState.FAILED;
        this.customerId = 0;
        this.durationMs = 0;
    }

    public synchronized void markAvailable() {
        ownerId.set(0);
        this.state = MachineState.AVAILABLE;
        this.customerId = 0;
        this.durationMs = 0;
    }

    public synchronized MachineView view(long nowNanos) {
        double remaining = 0;
        double progress = 0;
        if (state == MachineState.IN_USE && durationMs > 0) {
            double elapsedMs = (nowNanos - startNanos) / 1_000_000.0;
            remaining = Math.max(0, durationMs - elapsedMs) / 1000.0;
            progress = Math.min(1, Math.max(0, elapsedMs / durationMs));
        }

        return new MachineView(name, state, state.label(), customerId,
                Math.round(remaining * 10) / 10.0, Math.round(progress * 100) / 100.0);
    }
}
