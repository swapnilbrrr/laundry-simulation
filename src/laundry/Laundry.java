package laundry;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The shared laundromat: 6 washers, 4 dryers, 2 payment kiosks.
 *
 * This is the ONLY object customer threads touch, and it owns all
 * synchronisation policy, so customers stay simple: request a stage,
 * get served when a device frees up, move on.
 *
 * ------------------------------------------------------------------
 * CONCURRENCY DESIGN (the heart of the assignment)
 * ------------------------------------------------------------------
 * Each device class is guarded by two cooperating primitives:
 *
 *  1. Semaphore(permits = number of devices)
 *     - A customer must acquire() a permit before entering a stage and
 *       always releases it in a finally-block (exception-safety: a lost
 *       permit would permanently shrink capacity - a resource-leak bug
 *       that is easy to introduce and hard to see).
 *     - The blocking acquire() implements the "wait when all machines
 *       are occupied" requirement without busy-waiting: the JVM parks
 *       the thread and wakes it FIFO-ish when a permit is freed.
 *
 *  2. BlockingQueue (ArrayBlockingQueue) as the idle-device pool
 *     - take()/put() are internally locked and block when empty/full,
 *       giving a safe producer-consumer hand-off of device objects.
 *     - Combined with the semaphore this guarantees exclusive ownership:
 *       a device taken from the pool cannot be taken by anyone else
 *       until its owner puts it back.
 *
 *  3. synchronized (in Machine.runCycle / Logger) for the small critical
 *     sections - the mutual-exclusion requirement of the brief.
 *
 *  4. AtomicInteger / AtomicLong in Stats for lock-free, atomic
 *     counters and peak tracking.
 *
 * Why not plain 'synchronized(this)' around whole stages? Because a
 * monitor on Laundry would serialise ALL customers onto ONE device -
 * the washers, dryers and kiosks must operate simultaneously
 * (additional requirement 4). Semaphores give bounded, multi-slot
 * concurrency instead of mutual exclusion.
 */
public class Laundry {

    private final boolean congestedScenario;
    private volatile boolean ownerCalled = false;
    private volatile long startMillis = 0L;

    public static final int WASHERS = 6;
    public static final int DRYERS = 4;
    public static final int KIOSKS = 2;

    /** Additional requirement 2: failure probabilities per cycle attempt. */
    private static final double WASHER_FAILURE_P = 0.05;
    private static final double KIOSK_FAILURE_P = 0.05;

    /** Timing windows from the brief (milliseconds). */
    public static final int WASH_MIN = 4000, WASH_MAX = 6000;
    public static final int DRY_MIN = 3000, DRY_MAX = 5000;
    public static final int PAY_MIN = 1000, PAY_MAX = 2000;

    /** Bonus congested scenario: owner is called once 30 customers wait at payment. */
    public static final int OWNER_CALLED_AT = 30;

    private final Logger log = new Logger();
    private final Stats stats = new Stats();

    private final Semaphore washPermits = new Semaphore(WASHERS, true);
    private final Semaphore dryPermits = new Semaphore(DRYERS, true);
    private final Semaphore payPermits = new Semaphore(KIOSKS, true);

    private final BlockingQueue<Machine> idleWashers = new ArrayBlockingQueue<>(WASHERS);
    private final BlockingQueue<Machine> idleDryers = new ArrayBlockingQueue<>(DRYERS);
    private final BlockingQueue<Machine> idleKiosks = new ArrayBlockingQueue<>(KIOSKS);

    private final List<Machine> allWashers = new java.util.ArrayList<>();
    private final List<Machine> allDryers = new java.util.ArrayList<>();
    private final List<Machine> allKiosks = new java.util.ArrayList<>();

    /** Live in-use counters, purely for "max concurrent" statistics. */
    private final AtomicInteger washersInUse = new AtomicInteger();
    private final AtomicInteger dryersInUse = new AtomicInteger();

    /** Congestion listener handed in by Simulation (bonus scenario). */
    private volatile Runnable paymentQueueListener = null;

    /** Kiosk hard-outage flag for the congested scenario (volatile: one writer, many readers). */
    private volatile boolean kiosksDown = false;

    public Laundry(boolean congestedScenario) {
        this.congestedScenario = congestedScenario;
        for (int i = 1; i <= WASHERS; i++) {
            Machine m = new Machine(i, Machine.Kind.WASHER);
            allWashers.add(m);
            idleWashers.add(m);
        }
        for (int i = 1; i <= DRYERS; i++) {
            Machine m = new Machine(i, Machine.Kind.DRYER);
            allDryers.add(m);
            idleDryers.add(m);
        }
        for (int i = 1; i <= KIOSKS; i++) {
            Machine m = new Machine(i, Machine.Kind.KIOSK);
            if (congestedScenario) {
                // Bonus: both kiosks are broken from opening time; every cycle fails
                // instantly until the owner (Simulation) repairs them.
                m.markOutOfService();
                m.hardOutageCheck = () -> {
                    if (kiosksDown) {
                        throw new KioskDownException();
                    }
                };
            }
            allKiosks.add(m);
            idleKiosks.add(m);
        }
        if (congestedScenario) {
            kiosksDown = true;
        }
    }

    /** Thrown by a kiosk that is permanently out of order (bonus scenario). */
    public static class KioskDownException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    public Logger log() { return log; }
    public Stats stats() { return stats; }
    public void setPaymentQueueListener(Runnable r) { this.paymentQueueListener = r; }

    public void markStarted() { startMillis = System.currentTimeMillis(); }

    public double elapsedSeconds() {
        if (startMillis == 0L) return 0.0;
        return (System.currentTimeMillis() - startMillis) / 1000.0;
    }

    public boolean isCongestedScenario() { return congestedScenario; }
    public boolean ownerCalled() { return ownerCalled; }
    public void markOwnerCalled() { ownerCalled = true; }

    /* =====================================================================
     * STAGE 1 - WASHING (basic requirement 2, error handling requirement 2)
     * ===================================================================== */

    /**
     * Wash with automatic retry: a 5% chance exists that the washer fails
     * mid-cycle. The failed machine is then withdrawn from the idle pool
     * (so nobody else can grab a broken device) and a repair thread puts it
     * back later; the customer re-queues for another washer.
     *
     * Note the single finally-block: the device and its semaphore permit are
     * returned on EVERY exit path - success, failure or interruption. A
     * partially-returning path is a classic concurrency leak that would show
     * up as a gradually shrinking washer count and finally a deadlock.
     */
    public void wash(Customer c) throws InterruptedException {
        while (true) {
            Machine washer = acquire(washPermits, idleWashers);
            boolean withdrawnForRepair = false;
            try {
                washer.failureScheduled = ThreadLocalRandom.current().nextDouble() < WASHER_FAILURE_P;
                washersInUse.incrementAndGet();
                stats.noteWashersInUse(washersInUse.get());
                boolean ok;
                try {
                    ok = washer.runCycle("Washing", random(WASH_MIN, WASH_MAX), log);
                } finally {
                    washersInUse.decrementAndGet();
                }
                if (ok) {
                    log.info(c.getName(), "finished washing on " + washer);
                    return;
                }
                stats.noteWasherFailure();
                withdrawnForRepair = true;   // keep permit: capacity is genuinely reduced
                scheduleRepair(washer, 1500);
                log.info(c.getName(), "will retry washing - rejoining the washer queue.");
            } finally {
                if (!withdrawnForRepair) {
                    idleWashers.put(washer);
                    washPermits.release();
                }
            }
        }
    }

    /* =====================================================================
     * STAGE 2 - DRYING (basic requirement 3 - dryers never fail per brief)
     * ===================================================================== */

    public void dry(Customer c) throws InterruptedException {
        Machine dryer = acquire(dryPermits, idleDryers);
        try {
            dryersInUse.incrementAndGet();
            stats.noteDryersInUse(dryersInUse.get());
            try {
                int duration = random(DRY_MIN, DRY_MAX);
                dryer.runCycle("Drying", duration, log);
            } finally {
                dryersInUse.decrementAndGet();
            }
            log.info(c.getName(), "finished drying on " + dryer);
        } finally {
            idleDryers.put(dryer);
            dryPermits.release();
        }
    }

    /* =====================================================================
     * STAGE 3 - PAYMENT (basic requirement 4 + 5% kiosk failure, retry after 2s)
     * ===================================================================== */

    /**
     * Additional requirement 2: a kiosk fails with 5% probability per
     * attempt; the customer then waits 2 seconds and tries again.
     *
     * Queue semantics matter here: the customer JOINES the payment queue once
     * (when entering this method) and only LEAVES it when payment succeeds.
     * Counting per-attempt instead would let the queue appear empty during the
     * 2s retry sleeps, so the congested scenario would never reach 30 people
     * and the simulation would spin forever - a real bug found while testing.
     */
    public void pay(Customer c) throws InterruptedException {
        int queued = stats.joinPaymentQueue();
        Runnable listener = paymentQueueListener;
        if (listener != null) {
            listener.run(); // bonus scenario: owner is called once 30 customers pile up
        }
        try {
            int attempt = 0;
            while (true) {
                attempt++;
                boolean served;
                Machine kiosk = acquire(payPermits, idleKiosks);
                boolean congested = kiosksDown;
                try {
                    kiosk.failureScheduled = !congested
                            && ThreadLocalRandom.current().nextDouble() < KIOSK_FAILURE_P;
                    try {
                        served = kiosk.runCycle("Payment", random(PAY_MIN, PAY_MAX), log);
                    } catch (KioskDownException down) {
                        served = false;
                        log.warn(c.getName(), kiosk + " is out of order (" + queued
                                + " customers waiting at payment)");
                    }
                } finally {
                    idleKiosks.put(kiosk);
                    payPermits.release();
                }
                if (served) {
                    log.info(c.getName(), "paid at " + kiosk
                            + (attempt > 1 ? " (attempt " + attempt + ")" : ""));
                    return;
                }
                if (!congested) {
                    kiosk.repair(); // the 5% transient glitch clears before the next customer
                    log.info(c.getName(), "payment failed; retrying in 2000ms.");
                }
                stats.noteKioskFailure();
                // Additional requirement 2: kiosk failure -> retry after 2 seconds.
                Thread.sleep(2000);
            }
        } finally {
            stats.leavePaymentQueue();
        }
    }

    /* =====================================================================
     * Shared helpers
     * ===================================================================== */

    /**
     * Acquire a stage permit then take an idle device.
     * If the thread is interrupted after taking the permit but before
     * owning a device, the permit is put back (no lost-permit race).
     */
    private Machine acquire(Semaphore permits, BlockingQueue<Machine> pool) throws InterruptedException {
        permits.acquire();
        try {
            return pool.take();
        } catch (InterruptedException e) {
            permits.release();
            throw e;
        }
    }

    /**
     * Failed washers are withdrawn from service; this background thread
     * repairs them and returns both the device (to the idle pool) and its
     * semaphore permit, restoring full capacity.
     */
    private void scheduleRepair(Machine m, long delayMillis) {
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            m.repair();
            idleWashers.add(m);
            washPermits.release();
            log.event(m + " repaired and back in service.");
        }, "repair-" + m);
        t.setDaemon(true);
        t.start();
    }

    private static int random(int min, int max) {
        return ThreadLocalRandom.current().nextInt(min, max + 1);
    }

    /** Opens the kiosks again (owner arrives - bonus scenario). */
    public void ownerRepairsKiosks() {
        kiosksDown = false;
        for (Machine k : allKiosks) {
            k.repair();
        }
        log.event("OWNER ARRIVED! Both payment kiosks repaired - the queue starts moving again.");
    }

    /* Accessors used by the Swing GUI to render the live state. */
    public List<Machine> washers() { return allWashers; }
    public List<Machine> dryers() { return allDryers; }
    public List<Machine> kiosks() { return allKiosks; }
}
