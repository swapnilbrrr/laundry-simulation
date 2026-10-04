package com.smartlaundry.service;

import com.smartlaundry.model.SimulationStatistics;
import com.smartlaundry.resource.ResourceManager;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/** Thread-safe shared statistics for the running facility. */
@Service
public class StatisticsService {
    private final AtomicInteger arrived = new AtomicInteger();
    private final AtomicInteger served = new AtomicInteger();
    private final AtomicInteger washFailures = new AtomicInteger();
    private final AtomicInteger payFailures = new AtomicInteger();
    private final AtomicInteger washRetries = new AtomicInteger();
    private final AtomicInteger payRetries = new AtomicInteger();
    private final AtomicInteger congestionEvents = new AtomicInteger();
    private final AtomicBoolean ownerCalled = new AtomicBoolean();

    private final LongAdder totalTimeNanos = new LongAdder();
    private final LongAdder washWaitNanos = new LongAdder();
    private final LongAdder dryWaitNanos = new LongAdder();
    private final LongAdder payWaitNanos = new LongAdder();
    private final LongAdder washWaits = new LongAdder();
    private final LongAdder dryWaits = new LongAdder();
    private final LongAdder payWaits = new LongAdder();

    public void reset() {
        arrived.set(0);
        served.set(0);
        washFailures.set(0);
        payFailures.set(0);
        washRetries.set(0);
        payRetries.set(0);
        congestionEvents.set(0);
        ownerCalled.set(false);
        totalTimeNanos.reset();
        washWaitNanos.reset();
        dryWaitNanos.reset();
        payWaitNanos.reset();
        washWaits.reset();
        dryWaits.reset();
        payWaits.reset();
    }

    public void recordArrival() {
        arrived.incrementAndGet();
    }

    public void recordCompleted(long totalNanos) {
        totalTimeNanos.add(totalNanos);
        served.incrementAndGet();
    }

    public void recordWashWait(long nanos) {
        washWaitNanos.add(nanos);
        washWaits.increment();
    }

    public void recordDryWait(long nanos) {
        dryWaitNanos.add(nanos);
        dryWaits.increment();
    }

    public void recordPaymentWait(long nanos) {
        payWaitNanos.add(nanos);
        payWaits.increment();
    }

    public void recordWashFailure() { washFailures.incrementAndGet(); }
    public void recordPaymentFailure() { payFailures.incrementAndGet(); }
    public void recordWashRetry() { washRetries.incrementAndGet(); }
    public void recordPaymentRetry() { payRetries.incrementAndGet(); }
    public void recordCongestionEvent() { congestionEvents.incrementAndGet(); }

    /** CAS ensures that only one customer can win the owner-call transition. */
    public boolean markOwnerCalled() {
        return ownerCalled.compareAndSet(false, true);
    }

    private static double avgSeconds(LongAdder nanos, long count) {
        return count == 0 ? 0.0
                : Math.round((nanos.sum() / (double) count) / 10_000_000.0) / 100.0;
    }

    public SimulationStatistics snapshot(ResourceManager resources, int totalCustomers, long elapsedMs) {
        int done = served.get();
        int arrivedNow = arrived.get();
        double throughput = elapsedMs > 0
                ? Math.round(done / (elapsedMs / 60_000.0) * 10.0) / 10.0
                : 0.0;

        return new SimulationStatistics(
                arrivedNow,
                done,
                totalCustomers,
                Math.max(0, arrivedNow - done),
                avgSeconds(totalTimeNanos, done),
                avgSeconds(washWaitNanos, washWaits.sum()),
                avgSeconds(dryWaitNanos, dryWaits.sum()),
                avgSeconds(payWaitNanos, payWaits.sum()),
                resources.washers().inUse(),
                resources.washers().peakInUse(),
                resources.dryers().inUse(),
                resources.dryers().peakInUse(),
                resources.kiosks().inUse(),
                resources.kiosks().peakInUse(),
                resources.washers().waitingCount(),
                resources.dryers().waitingCount(),
                resources.kiosks().waitingCount(),
                resources.kiosks().peakWaiting(),
                washFailures.get(),
                payFailures.get(),
                washRetries.get(),
                payRetries.get(),
                congestionEvents.get(),
                ownerCalled.get(),
                throughput,
                Math.round(elapsedMs / 100.0) / 10.0);
    }
}
