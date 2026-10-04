package com.smartlaundry.simulation;

import com.smartlaundry.concurrency.PauseController;
import com.smartlaundry.exception.SimulationException;
import com.smartlaundry.model.CustomerSnapshot;
import com.smartlaundry.model.CustomerState;
import com.smartlaundry.model.EventType;
import com.smartlaundry.model.FacilitySnapshot;
import com.smartlaundry.model.MachineState;
import com.smartlaundry.model.PipelineCounts;
import com.smartlaundry.resource.LaundryResource;
import com.smartlaundry.resource.PaymentKiosk;
import com.smartlaundry.resource.ResourceManager;
import com.smartlaundry.resource.ResourcePool;
import com.smartlaundry.service.EventService;
import com.smartlaundry.service.StatisticsService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One isolated simulation run. Every customer is represented by its own
 * {@link Thread}. The auxiliary executor is reserved for the arrival
 * coordinator, congestion monitor and resource-recovery jobs.
 */
public final class LaundrySimulation {
    private static final AtomicInteger RUN_COUNTER = new AtomicInteger();

    private final int runId = RUN_COUNTER.incrementAndGet();
    private final SimulationConfig config;
    private final SimulationMode mode;
    private final EventService events;
    private final StatisticsService stats;
    private final ResourceManager resources;
    private final PauseController pause = new PauseController();
    private final FailureInjector failures;

    private final List<Customer> customers = new CopyOnWriteArrayList<>();
    private final List<Thread> customerThreads = new CopyOnWriteArrayList<>();
    private final AtomicInteger finishedCustomers = new AtomicInteger();
    private final CompletableFuture<Void> completion = new CompletableFuture<>();
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicReference<OwnerStatus> ownerStatus = new AtomicReference<>(OwnerStatus.NOT_REQUIRED);

    private final ExecutorService auxiliary = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "run-auxiliary");
        t.setDaemon(true);
        return t;
    });

    private volatile boolean stopRequested;
    private volatile List<PaymentKiosk> offlineKiosks = List.of();

    public LaundrySimulation(SimulationConfig config, SimulationMode mode, EventService events, StatisticsService stats) {
        this.config = config;
        this.mode = mode;
        this.events = events;
        this.stats = stats;
        this.failures = new FailureInjector(config);
        this.resources = new ResourceManager(SimulationConfig.WASHERS, SimulationConfig.DRYERS, SimulationConfig.KIOSKS);
    }

    public void start() {
        if (!started.compareAndSet(false, true)) {
            throw new SimulationException("This simulation run was already started");
        }

        pause.markStart();
        events.publish(EventType.SYSTEM, 0, "Facility", "-",
                "Simulation started - mode " + mode + ", " + config.customers() + " customers, "
                        + SimulationConfig.WASHERS + " washers, " + SimulationConfig.DRYERS + " dryers, "
                        + SimulationConfig.KIOSKS + " kiosks");

        if (mode == SimulationMode.CONGESTED) {
            offlineKiosks = resources.kiosks().takeAllOffline();
            ownerStatus.set(OwnerStatus.PENDING);
            events.publish(EventType.CONGESTION, 0, "Kiosk", "-",
                    "Both payment kiosks are OUT OF SERVICE for the day (Congested Mode)");
            auxiliary.execute(this::monitorCongestion);
        }

        auxiliary.execute(this::runArrivals);
    }

    public void pauseRun() { pause.pause(); }
    public void resumeRun() { pause.resume(); }

    /** Interrupts the customer threads and auxiliary tasks belonging only to this run. */
    public void stop() {
        stopRequested = true;
        pause.resume();

        for (Thread thread : customerThreads) {
            thread.interrupt();
        }
        auxiliary.shutdownNow();

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        for (Thread thread : customerThreads) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) break;
            try {
                thread.join(TimeUnit.NANOSECONDS.toMillis(remaining));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        try {
            auxiliary.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        pause.markEnd();
        resources.restoreAll();
        offlineKiosks = List.of();
    }

    /** Normal end: customer threads already completed; shut down only auxiliary simulation work. */
    public void finish() {
        pause.markEnd();
        auxiliary.shutdown();
    }

    private void runArrivals() {
        Thread.currentThread().setName("Arrival-Generator");
        try {
            for (int i = 1; i <= config.customers() && !stopRequested; i++) {
                pause.sleep(ThreadLocalRandom.current().nextInt(0, config.arrivalMaxMs() + 1));
                if (stopRequested) break;

                Customer customer = new Customer(i, this);
                customers.add(customer);
                Thread customerThread = new Thread(customer, customer.name());
                customerThread.setDaemon(true);
                customerThreads.add(customerThread);
                customerThread.start();
            }

            for (Thread thread : customerThreads) {
                thread.join();
            }
            completeIfAllCustomersFinished();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RejectedExecutionException ignored) {
            // Stop may close the auxiliary executor while the generator is between arrivals.
        }
    }

    private void monitorCongestion() {
        Thread.currentThread().setName("Owner-Monitor");
        int threshold = Math.min(config.congestionThreshold(), config.customers());
        try {
            while (!stopRequested && ownerStatus.get() == OwnerStatus.PENDING) {
                if (resources.kiosks().waitingCount() >= threshold) {
                    callOwner(threshold);
                    return;
                }
                pause.sleep(100);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void callOwner(int threshold) throws InterruptedException {
        if (!stats.markOwnerCalled()) {
            return;
        }
        ownerStatus.set(OwnerStatus.CALLED);
        stats.recordCongestionEvent();
        events.publish(EventType.CONGESTION, 0, "Kiosk", "-", "PAYMENT CONGESTION DETECTED");
        events.publish(EventType.CONGESTION, 0, "Kiosk", "-", threshold + " CUSTOMERS WAITING");
        events.publish(EventType.CONGESTION, 0, "Kiosk", "-", "OWNER HAS BEEN CALLED");
        pause.sleep(config.ownerResponseMs());
        if (stopRequested) return;
        resources.kiosks().restore(offlineKiosks);
        offlineKiosks = List.of();
        ownerStatus.set(OwnerStatus.RESTORED);
        events.publish(EventType.SYSTEM, 0, "Kiosk", "-",
                "Owner repaired both kiosks - payment processing resumes");
    }

    <T extends LaundryResource> void scheduleRecovery(ResourcePool<T> pool, T machine, int totalMs) {
        Runnable task = () -> {
            Thread.currentThread().setName("Recovery-" + machine.getName());
            boolean interrupted = false;
            try {
                pause.sleep(totalMs / 2);
                machine.setState(MachineState.RECOVERING);
                events.publish(EventType.SYSTEM, 0, machine.getType(), machine.getName(),
                        machine.getName() + " recovery in progress");
                pause.sleep(totalMs - totalMs / 2);
            } catch (InterruptedException e) {
                interrupted = true;
                Thread.currentThread().interrupt();
            } finally {
                pool.recover(machine);
                if (!interrupted && !stopRequested) {
                    events.publish(EventType.SUCCESS, 0, machine.getType(), machine.getName(),
                            machine.getName() + " recovery completed - available again");
                }
            }
        };
        try {
            auxiliary.execute(task);
        } catch (RejectedExecutionException e) {
            pool.recover(machine);
        }
    }

    void customerFinished() {
        if (finishedCustomers.incrementAndGet() == config.customers()) {
            completeIfAllCustomersFinished();
        }
    }

    private void completeIfAllCustomersFinished() {
        if (!stopRequested
                && finishedCustomers.get() == config.customers()
                && customerThreads.size() == config.customers()) {
            completion.complete(null);
        }
    }

    int randomBetween(int minMs, int maxMs) {
        return ThreadLocalRandom.current().nextInt(minMs, maxMs + 1);
    }

    public FacilitySnapshot snapshot(SimulationState state) {
        long now = pause.simNanos();
        long base = pause.startSimNanos();
        int[] count = new int[CustomerState.values().length];
        List<CustomerSnapshot> rows = new ArrayList<>();
        for (Customer c : customers) {
            CustomerState s = c.state();
            count[s.ordinal()]++;
            rows.add(c.snapshot(now, base));
        }
        PipelineCounts pipeline = new PipelineCounts(rows.size(),
                count[CustomerState.WAITING_WASHER.ordinal()], count[CustomerState.WASHING.ordinal()],
                count[CustomerState.WAITING_DRYER.ordinal()], count[CustomerState.DRYING.ordinal()],
                count[CustomerState.WAITING_PAYMENT.ordinal()] + count[CustomerState.PAYMENT_RETRY_WAIT.ordinal()],
                count[CustomerState.PAYING.ordinal()], count[CustomerState.COMPLETED.ordinal()], config.customers());
        return new FacilitySnapshot(state, mode, config.failureMode(), pause.elapsedMillis(), config.customers(),
                resources.washers().views(now), resources.dryers().views(now), resources.kiosks().views(now),
                resources.washers().waitingSnapshot(), resources.dryers().waitingSnapshot(),
                resources.kiosks().waitingSnapshot(), pipeline, rows,
                stats.snapshot(resources, config.customers(), pause.elapsedMillis()),
                ownerStatus.get(), config.congestionThreshold());
    }

    public SimulationConfig config() { return config; }
    public SimulationMode mode() { return mode; }
    public EventService events() { return events; }
    public StatisticsService stats() { return stats; }
    public ResourceManager resources() { return resources; }
    public PauseController pause() { return pause; }
    public FailureInjector failures() { return failures; }
    public List<Customer> customers() { return Collections.unmodifiableList(customers); }
    public CompletableFuture<Void> completion() { return completion; }
    public OwnerStatus ownerStatus() { return ownerStatus.get(); }
    public boolean isTerminated() { return auxiliary.isTerminated(); }
}
