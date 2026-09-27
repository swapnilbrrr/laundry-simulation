package laundry;

/**
 * One customer = one thread (basic requirement 1).
 *
 * A customer lives the full lifecycle:
 *   arrive -> (wait for a washer) -> wash -> (wait for a dryer) -> dry
 *          -> (wait for a kiosk)  -> pay  -> leave.
 *
 * All blocking/waiting is delegated to Laundry's semaphores, so this run()
 * method stays a linear, readable script of the domain flow. The thread
 * only ever acts for ITSELF - it never mutates another customer's state,
 * which is exactly what the brief's sample-output rule demands.
 */
public class Customer implements Runnable {

    private final int no;
    private final Laundry laundry;
    private final long arrivalMillis;

    public Customer(int no, Laundry laundry, long arrivalMillis) {
        this.no = no;
        this.laundry = laundry;
        this.arrivalMillis = arrivalMillis;
    }

    public String getName() {
        return "Customer-" + String.format("%02d", no);
    }

    @Override
    public void run() {
        Logger log = laundry.log();
        try {
            laundry.wash(this);
            laundry.dry(this);
            laundry.pay(this);
            long totalTime = System.currentTimeMillis() - arrivalMillis;
            laundry.stats().customerFinished(totalTime);
            log.info(getName(), "done - left the laundromat after "
                    + String.format("%.2f", totalTime / 1000.0) + "s total.");
        } catch (InterruptedException e) {
            log.warn(getName(), "interrupted and left without finishing.");
            Thread.currentThread().interrupt(); // restore flag - correct interruption protocol
        }
    }
}
