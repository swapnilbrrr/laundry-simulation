package com.smartlaundry.simulation;

import com.smartlaundry.model.CustomerSnapshot;
import com.smartlaundry.model.CustomerState;
import com.smartlaundry.model.EventType;
import com.smartlaundry.resource.Dryer;
import com.smartlaundry.resource.PaymentKiosk;
import com.smartlaundry.resource.ResourcePool;
import com.smartlaundry.resource.WashingMachine;

/**
 * One customer = one Java thread (named Customer-NN). It runs the mandatory lifecycle
 * ARRIVAL -> WASH -> DRY -> PAY -> EXIT strictly in order. At every moment a customer holds at most ONE resource:
 * the washer is released before queueing for a dryer, the dryer before queueing for a kiosk (no hold-and-wait,
 * so no circular wait => no deadlock). All timestamps use the pause-aware simulation clock.
 */
public final class Customer implements Runnable {
    private static final String NONE = "-";

    private final int id;
    private final String name;
    private final LaundrySimulation sim;
    private final SimulationConfig cfg;

    private volatile CustomerState state = CustomerState.ARRIVED;
    private volatile String resourceName = NONE;
    private volatile boolean retrying;

    private final long arrival;
    private volatile long washWaitStart = -1, washStart = -1, washEnd = -1;
    private volatile long dryWaitStart = -1, dryStart = -1, dryEnd = -1;
    private volatile long payWaitStart = -1, payStart = -1, payEnd = -1;
    private volatile long completed = -1;
    private volatile long paymentRetryDelayMs = -1;

    /** Immutable copy of all recorded timestamps (used for statistics and tests). */
    public record Timeline(long arrival, long washWaitStart, long washStart, long washEnd,
                           long dryWaitStart, long dryStart, long dryEnd,
                           long payWaitStart, long payStart, long payEnd, long completed) {
    }

    public Customer(int id, LaundrySimulation sim) {
        this.id = id;
        this.name = String.format("Customer-%02d", id);
        this.sim = sim;
        this.cfg = sim.config();
        this.arrival = now();
    }

    @Override
    public void run() {
        Thread.currentThread().setName(name);
        try {
            enterFacility();
            washingStage();
            dryingStage();
            paymentStage();
            leaveFacility();
            sim.customerFinished();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            state = CustomerState.CANCELLED;
            resourceName = NONE;
            log(EventType.SYSTEM, NONE, NONE, "Simulation stopped - leaving the facility");
        } catch (RuntimeException e) {
            state = CustomerState.CANCELLED;
            resourceName = NONE;
            log(EventType.FAILURE, NONE, NONE, "Unexpected error: " + e);
            sim.customerFinished();
        }
    }

    private void enterFacility() {
        state = CustomerState.ARRIVED;
        sim.stats().recordArrival();
        log(EventType.INFO, "Entry", NONE, "Entered laundry facility through entry gate");
    }

    private void washingStage() throws InterruptedException {
        ResourcePool<WashingMachine> pool = sim.resources().washers();
        int attempt = 0;
        boolean washed = false;
        while (!washed) {
            attempt++;
            if (attempt > 1) {
                retrying = true;
                sim.stats().recordWashRetry();
            }
            state = CustomerState.WAITING_WASHER;
            washWaitStart = now();
            if (attempt == 1) {
                log(EventType.WAIT, "Washer", NONE, "Waiting for washing machine");
            } else {
                log(EventType.RETRY, "Washer", NONE, "Retrying washing - rejoining washer queue");
            }
            WashingMachine machine = pool.acquire(id);
            washed = washOn(machine, pool, attempt);
        }
    }

    private boolean washOn(WashingMachine machine, ResourcePool<WashingMachine> pool, int attempt)
            throws InterruptedException {
        boolean handedToRecovery = false;
        try {
            washStart = now();
            sim.stats().recordWashWait(washStart - washWaitStart);
            int duration = sim.randomBetween(cfg.washMinMs(), cfg.washMaxMs());
            state = CustomerState.WASHING;
            retrying = false;
            resourceName = machine.getName();
            machine.beginUse(id, duration, washStart);
            log(EventType.INFO, "Washer", machine.getName(), "Acquired " + machine.getName());
            log(EventType.INFO, "Washer", machine.getName(),
                    String.format("Washing started for %.1f seconds", duration / 1000.0));

            if (sim.failures().shouldFailWash(id, attempt)) {
                sim.pause().sleep(duration / 2);
                pool.markFailed(machine);
                handedToRecovery = true;
                sim.stats().recordWashFailure();
                log(EventType.FAILURE, "Washer", machine.getName(), machine.getName() + " failed during washing.");
                log(EventType.RETRY, "Washer", machine.getName(), "Washing unsuccessful. Rejoining washer queue.");
                sim.scheduleRecovery(pool, machine, cfg.washRecoveryMs());
                return false;
            }

            sim.pause().sleep(duration);
            washEnd = now();
            log(EventType.SUCCESS, "Washer", machine.getName(), "Washing completed");
            return true;
        } finally {
            if (!handedToRecovery) {
                pool.release(machine);
                log(EventType.INFO, "Washer", machine.getName(), "Released " + machine.getName());
            }
            resourceName = NONE;
        }
    }

    private void dryingStage() throws InterruptedException {
        ResourcePool<Dryer> pool = sim.resources().dryers();
        state = CustomerState.WAITING_DRYER;
        dryWaitStart = now();
        log(EventType.WAIT, "Dryer", NONE, "Waiting for dryer");
        Dryer dryer = pool.acquire(id);
        try {
            dryStart = now();
            sim.stats().recordDryWait(dryStart - dryWaitStart);
            int duration = sim.randomBetween(cfg.dryMinMs(), cfg.dryMaxMs());
            state = CustomerState.DRYING;
            resourceName = dryer.getName();
            dryer.beginUse(id, duration, dryStart);
            log(EventType.INFO, "Dryer", dryer.getName(), "Acquired " + dryer.getName());
            log(EventType.INFO, "Dryer", dryer.getName(),
                    String.format("Drying started for %.1f seconds", duration / 1000.0));
            sim.pause().sleep(duration);
            dryEnd = now();
            log(EventType.SUCCESS, "Dryer", dryer.getName(), "Drying completed");
        } finally {
            pool.release(dryer);
            log(EventType.INFO, "Dryer", dryer.getName(), "Released " + dryer.getName());
            resourceName = NONE;
        }
    }

    private void paymentStage() throws InterruptedException {
        ResourcePool<PaymentKiosk> pool = sim.resources().kiosks();
        int attempt = 0;
        boolean paid = false;
        while (!paid) {
            attempt++;
            if (attempt > 1) {
                sim.stats().recordPaymentRetry();
            }
            state = CustomerState.WAITING_PAYMENT;
            payWaitStart = now();
            if (attempt == 1) {
                log(EventType.WAIT, "Kiosk", NONE, "Waiting for payment");
            } else {
                log(EventType.RETRY, "Kiosk", NONE, "Rejoining payment queue");
            }
            PaymentKiosk kiosk = pool.acquire(id);
            paid = payAt(kiosk, pool, attempt);
            if (!paid) {
                state = CustomerState.PAYMENT_RETRY_WAIT;
                retrying = true;
                log(EventType.RETRY, "Kiosk", NONE,
                        String.format("Waiting %.0f seconds before retry", cfg.payRetryMs() / 1000.0));
                long before = now();
                sim.pause().sleep(cfg.payRetryMs());
                paymentRetryDelayMs = (now() - before) / 1_000_000;
            }
        }
    }

    private boolean payAt(PaymentKiosk kiosk, ResourcePool<PaymentKiosk> pool, int attempt)
            throws InterruptedException {
        boolean handedToRecovery = false;
        try {
            payStart = now();
            sim.stats().recordPaymentWait(payStart - payWaitStart);
            int duration = sim.randomBetween(cfg.payMinMs(), cfg.payMaxMs());
            state = CustomerState.PAYING;
            retrying = false;
            resourceName = kiosk.getName();
            kiosk.beginUse(id, duration, payStart);
            log(EventType.INFO, "Kiosk", kiosk.getName(), "Acquired " + kiosk.getName());
            log(EventType.INFO, "Kiosk", kiosk.getName(),
                    String.format("Payment processing for %.1f seconds", duration / 1000.0));
            sim.pause().sleep(duration);

            if (sim.failures().shouldFailPayment(id, attempt)) {
                pool.markFailed(kiosk);
                handedToRecovery = true;
                sim.stats().recordPaymentFailure();
                log(EventType.FAILURE, "Kiosk", kiosk.getName(), "Payment failed at " + kiosk.getName() + ".");
                sim.scheduleRecovery(pool, kiosk, cfg.kioskRecoveryMs());
                return false;
            }
            payEnd = now();
            log(EventType.SUCCESS, "Kiosk", kiosk.getName(), "Payment successful at " + kiosk.getName());
            return true;
        } finally {
            if (!handedToRecovery) {
                pool.release(kiosk);
                log(EventType.INFO, "Kiosk", kiosk.getName(), "Released " + kiosk.getName());
            }
            resourceName = NONE;
        }
    }

    private void leaveFacility() {
        completed = now();
        state = CustomerState.COMPLETED;
        sim.stats().recordCompleted(completed - arrival);
        log(EventType.SUCCESS, NONE, NONE,
                String.format("Left the facility (total time %.1f s)", (completed - arrival) / 1e9));
    }

    private long now() {
        return sim.pause().simNanos();
    }

    private void log(EventType type, String resourceType, String resourceId, String message) {
        sim.events().publish(type, id, resourceType, resourceId, message);
    }

    public int id() { return id; }
    public String name() { return name; }
    public CustomerState state() { return state; }
    public long paymentRetryDelayMillis() { return paymentRetryDelayMs; }

    public Timeline timeline() {
        return new Timeline(arrival, washWaitStart, washStart, washEnd, dryWaitStart, dryStart, dryEnd,
                payWaitStart, payStart, payEnd, completed);
    }

    private String status() {
        return switch (state) {
            case COMPLETED -> "Completed";
            case CANCELLED -> "Cancelled";
            case WASHING -> "Washing";
            case DRYING -> "Drying";
            case PAYING -> "Paying";
            case PAYMENT_RETRY_WAIT -> "Retrying";
            case WAITING_WASHER, WAITING_DRYER, WAITING_PAYMENT -> retrying ? "Retrying" : "Waiting";
            case ARRIVED -> "Waiting";
        };
    }

    CustomerSnapshot snapshot(long nowNanos, long baseNanos) {
        long end = completed >= 0 ? completed : nowNanos;
        return new CustomerSnapshot(id, name, state.label(), resourceName, status(),
                Math.round((arrival - baseNanos) / 1e8) / 10.0, Math.round((end - arrival) / 1e8) / 10.0);
    }
}
