package laundry;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Drives the whole simulation: spawns 50 customer threads with random
 * 0-3s inter-arrival gaps, waits for everyone, prints the statistics.
 *
 * Customers still arrive using the required random 0-3s gaps. After all 50
 * customers finish, a 90s minimum runtime is enforced if needed. This keeps
 * the completed run inside the assignment's 1-2 minute implementation window
 * without interrupting or cutting off a real customer cycle.
 *
 * Assumptions stated for the report:
 *  - A customer occupies exactly one device per stage, exclusively.
 *  - Failed washers self-repair ~1.5s later; failed kiosks cost the
 *    customer a 2s wait (per brief).
 *  - "Total time per customer" = arrival -> payment completed.
 *  - Customers do their own laundry: no thread acts for another.
 */
public class Simulation {

    public static final int CUSTOMERS = 50;
    public static final int ARRIVAL_MIN_MS = 0;
    public static final int ARRIVAL_MAX_MS = 3000;

    /** Minimum wall-clock runtime requested for the submitted simulation. */
    public static final long MIN_RUNTIME_MS = 90_000L;

    private final Laundry laundry;
    private final boolean congested;

    public Simulation(boolean congested) {
        this.congested = congested;
        this.laundry = new Laundry(congested);
    }

    public Laundry laundry() { return laundry; }

    public void run() throws InterruptedException {
        Logger.startClock();
        Logger log = laundry.log();
        laundry.markStarted();
        long start = System.currentTimeMillis();
        log.event("Smart Laundry opens - " + Laundry.WASHERS + " washers, "
                + Laundry.DRYERS + " dryers, " + Laundry.KIOSKS + " kiosks, "
                + CUSTOMERS + " customers expected."
                + (congested ? " [BONUS CONGESTED SCENARIO]" : ""));

        if (congested) {
            installOwnerTrigger();
        }

        List<Thread> customers = new ArrayList<>();
        for (int i = 1; i <= CUSTOMERS; i++) {
            long now = System.currentTimeMillis();
            Customer c = new Customer(i, laundry, now);
            Thread t = new Thread(c, c.getName());
            // A Java thread cannot reuse 'i' from the loop directly - each
            // customer object captures its own immutable identity instead.
            customers.add(t);
            t.start();
            int gap = ThreadLocalRandom.current().nextInt(ARRIVAL_MIN_MS, ARRIVAL_MAX_MS + 1);
            Thread.sleep(gap); // the arrival generator itself; customers run in parallel
        }

        for (Thread t : customers) {
            t.join(); // wait for every customer to leave - join() is the structured way to
                      // await thread termination without polling or sleeping "long enough"
        }

        long elapsedMillis = System.currentTimeMillis() - start;
        if (elapsedMillis < MIN_RUNTIME_MS) {
            long remaining = MIN_RUNTIME_MS - elapsedMillis;
            log.event(String.format(
                    "All 50 customers completed. Keeping the simulation window open for %.1fs to meet the 90s minimum.",
                    remaining / 1000.0));
            Thread.sleep(remaining);
            elapsedMillis = System.currentTimeMillis() - start;
        }

        double elapsed = elapsedMillis / 1000.0;
        log.event(String.format("Simulation closed after %.1f seconds.", elapsed));
        System.out.println(laundry.stats().report());
    }

    /**
     * Bonus requirement 1: both kiosks are dead all day; the owner is
     * called in once 30 customers are stuck in the payment queue and
     * fixes them. The trigger is a volatile-listener callback invoked by
     * the joining customer, not a polling loop, and an AtomicBoolean
     * compare-and-set guarantees exactly one owner is dispatched even if
     * several threads cross the threshold at the same instant.
     */
    private void installOwnerTrigger() {
        laundry.setPaymentQueueListener(() -> {
            int q = laundry.stats().waitingForPayment().get();
            if (q >= Laundry.OWNER_CALLED_AT && ownerCalled.compareAndSet(false, true)) {
                laundry.markOwnerCalled();
                laundry.log().event("OWNER CALLED IN: " + q + " customers jammed at payment!");
                Thread owner = new Thread(() -> {
                    try {
                        Thread.sleep(4000); // the owner needs 4s to drive to the shop
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    laundry.ownerRepairsKiosks();
                }, "owner");
                owner.setDaemon(true);
                owner.start();
            }
        });
    }

    private final java.util.concurrent.atomic.AtomicBoolean ownerCalled =
            new java.util.concurrent.atomic.AtomicBoolean(false);
}
