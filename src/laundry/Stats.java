package laundry;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Statistics collector (additional requirement 3).
 *
 * Concurrency notes:
 * - Every counter is updated from many customer threads at once, so all state
 *   lives in atomic variables: addAndGet()/incrementAndGet()/accumulateAndGet()
 *   are single, indivisible (atomic) hardware-level operations - the classic
 *   "atomic statement" guarantee that removes check-then-act races.
 * - Peaks are recorded with accumulateAndGet(current, max), which is atomic
 *   read-modify-write; a plain "if (x > max) max = x" would be a race.
 */
public class Stats {

    private final AtomicInteger served = new AtomicInteger();
    private final AtomicLong totalSeconds = new AtomicLong();   // summed over all customers (ms)
    private final AtomicInteger peakWashersInUse = new AtomicInteger();
    private final AtomicInteger peakDryersInUse = new AtomicInteger();
    private final AtomicInteger washerFailures = new AtomicInteger();
    private final AtomicInteger kioskFailures = new AtomicInteger();

    /** Customers currently standing in the payment area (queue + being served). */
    private final AtomicInteger paymentQueue = new AtomicInteger();

    /**
     * Cumulative number of customers that have ever joined the payment queue.
     * Kept separate from the instantaneous size because a customer retrying
     * after a failure remains "in the queue" - the bonus scenario counts the
     * jam by how many people have piled up, not by a momentary snapshot.
     */
    private final AtomicInteger paymentArrivals = new AtomicInteger();

    public void customerFinished(long durationMillis) {
        served.incrementAndGet();
        totalSeconds.addAndGet(durationMillis);
    }

    public int joinPaymentQueue() {
        paymentArrivals.incrementAndGet();
        return paymentQueue.incrementAndGet();
    }

    public void leavePaymentQueue() { paymentQueue.decrementAndGet(); }
    public int paymentQueueArrivedTotal() { return paymentArrivals.get(); }

    public void noteWashersInUse(int now) {
        peakWashersInUse.accumulateAndGet(now, Math::max);
    }

    public void noteDryersInUse(int now) {
        peakDryersInUse.accumulateAndGet(now, Math::max);
    }

    public void noteWasherFailure() { washerFailures.incrementAndGet(); }
    public void noteKioskFailure() { kioskFailures.incrementAndGet(); }

    /** Current number of customers queuing/being-served at the payment stage. */
    public AtomicInteger waitingForPayment() { return paymentQueue; }

    public int served() { return served.get(); }

    public double averageSeconds() {
        int n = served.get();
        return n == 0 ? 0 : totalSeconds.get() / 1000.0 / n;
    }

    public int peakWashers() { return peakWashersInUse.get(); }
    public int peakDryers() { return peakDryersInUse.get(); }
    public int washerFailures() { return washerFailures.get(); }
    public int kioskFailures() { return kioskFailures.get(); }

    public String report() {
        int n = served.get();
        double avg = n == 0 ? 0 : totalSeconds.get() / 1000.0 / n;
        return """
                --------------------------------------------------------------
                     SMART LAUNDRY - FINAL STATISTICS
                --------------------------------------------------------------
                 Total customers served      : %d
                 Average total time/customer : %.2f s
                 Max concurrent washers used : %d
                 Max concurrent dryers used  : %d
                 Washer mid-cycle failures   : %d
                 Kiosk failures              : %d
                --------------------------------------------------------------""".formatted(n, avg,
                peakWashersInUse.get(), peakDryersInUse.get(),
                washerFailures.get(), kioskFailures.get());
    }
}
