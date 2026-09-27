package laundry;

/**
 * A single laundry resource (washing machine, dryer, or payment kiosk).
 *
 * Concurrency notes:
 * - 'busy' and 'failed' are only mutated while the calling thread holds the
 *   resource's intrinsic monitor (synchronized methods), so they can never be
 *   observed in a torn/inconsistent state by another thread.
 * - The real mutual exclusion across customers is enforced in Laundry via a
 *   Semaphore (one permit per device) + a BlockingQueue "idle pool", so at
 *   most one customer ever reaches a given Machine at a time.
 */
public class Machine {

    public enum Kind { WASHER, DRYER, KIOSK }

    private final int id;
    private final Kind kind;

    /** Volatile: read by the GUI/monitor threads without holding the monitor. */
    private volatile boolean busy = false;
    private volatile boolean failed = false;

    /** Set by Laundry when a scheduled failure should hit this cycle. */
    boolean failureScheduled = false;

    /** When non-null, any cycle on this device fails immediately (bonus congested scenario). */
    Runnable hardOutageCheck = null;

    public Machine(int id, Kind kind) {
        this.id = id;
        this.kind = kind;
    }

    public int id() { return id; }
    public Kind kind() { return kind; }
    public boolean isBusy() { return busy; }
    public boolean isFailed() { return failed; }

    public String label() {
        String prefix = switch (kind) {
            case WASHER -> "Washer";
            case DRYER -> "Dryer";
            case KIOSK -> "Kiosk";
        };
        return prefix + "-" + id;
    }

    /**
     * Runs one service cycle on this device.
     * Returns true on success; false if the device failed mid-cycle
     * (the customer must then release the device and retry elsewhere/later).
     */
    public synchronized boolean runCycle(String taskName, int millis, Logger log) throws InterruptedException {
        if (hardOutageCheck != null) {
            hardOutageCheck.run(); // may throw SimulationFailure -> never serves while congested
        }
        busy = true;
        log.info(label(), taskName + " started for " + Thread.currentThread().getName()
                + " (" + millis + "ms)");
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            busy = false;
            throw e; // propagate: interrupt is the standard way to end a thread's cycle
        }
        busy = false;
        if (failureScheduled) {
            failureScheduled = false;
            failed = true;
            log.warn(label(), "MACHINE FAILURE mid-cycle! " + taskName + " aborted; device will self-repair shortly.");
            return false;
        }
        return true;
    }

    /** Simulates the maintenance crew repairing a failed device. */
    public synchronized void repair() {
        failed = false;
    }

    /** Puts a device out of service up-front (bonus congested scenario). */
    public synchronized void markOutOfService() {
        failed = true;
    }

    @Override
    public String toString() { return label(); }
}
