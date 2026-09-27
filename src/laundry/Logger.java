package laundry;

/**
 * Thread-safe console logger.
 *
 * Concurrency notes:
 * - All output funnels through synchronized methods on one shared Logger
 *   object, so lines written by competing customer threads can never
 *   interleave mid-line (the monitor makes each println a critical section).
 * - Timestamps are elapsed seconds since the simulation opened, taken from a
 *   volatile clock origin, so the console output itself proves the ~60s budget.
 */
public class Logger {

    private static volatile long originMillis = System.currentTimeMillis();

    /** Called once by Simulation before any customer thread starts. */
    public static void startClock() {
        originMillis = System.currentTimeMillis();
    }

    private String stamp() {
        double s = (System.currentTimeMillis() - originMillis) / 1000.0;
        return String.format("[%6.2fs]", s);
    }

    public synchronized void info(String source, String message) {
        System.out.println(stamp() + " [" + source + "] " + message);
    }

    public synchronized void warn(String source, String message) {
        System.out.println(stamp() + " [" + source + "] !! " + message);
    }

    public synchronized void event(String message) {
        System.out.println(stamp() + " {EVENT} " + message);
    }
}
