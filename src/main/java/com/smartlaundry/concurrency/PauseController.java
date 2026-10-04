package com.smartlaundry.concurrency;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Cooperative pause/resume gate and pause-aware simulation clock.
 * Threads sleep in short interruptible slices and block on a Condition when paused;
 * there is no spin-wait loop.
 */
public final class PauseController {
    private static final long SLICE_NANOS = TimeUnit.MILLISECONDS.toNanos(50);

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition resumed = lock.newCondition();
    private final long origin = System.nanoTime();

    private volatile boolean paused;
    private long pausedTotalNanos;
    private long pauseStartNanos;
    private volatile boolean started;
    private volatile boolean ended;
    private volatile long startSim;
    private volatile long endSim;

    public void pause() {
        lock.lock();
        try {
            if (!paused) {
                pauseStartNanos = System.nanoTime();
                paused = true;
            }
        } finally {
            lock.unlock();
        }
    }

    public void resume() {
        lock.lock();
        try {
            if (paused) {
                pausedTotalNanos += System.nanoTime() - pauseStartNanos;
                paused = false;
                resumed.signalAll();
            }
        } finally {
            lock.unlock();
        }
    }

    public boolean isPaused() {
        return paused;
    }

    public void awaitIfPaused() throws InterruptedException {
        if (!paused) return;
        lock.lockInterruptibly();
        try {
            while (paused) resumed.await();
        } finally {
            lock.unlock();
        }
    }

    public void sleep(long millis) throws InterruptedException {
        long remaining = TimeUnit.MILLISECONDS.toNanos(millis);
        while (remaining > 0) {
            awaitIfPaused();
            long slice = Math.min(remaining, SLICE_NANOS);
            TimeUnit.NANOSECONDS.sleep(slice);
            remaining -= slice;
        }
    }

    public long simNanos() {
        lock.lock();
        try {
            long now = System.nanoTime();
            long currentPause = paused ? now - pauseStartNanos : 0;
            return now - origin - pausedTotalNanos - currentPause;
        } finally {
            lock.unlock();
        }
    }

    public void markStart() {
        startSim = simNanos();
        started = true;
    }

    public void markEnd() {
        if (started && !ended) {
            endSim = simNanos();
            ended = true;
        }
    }

    public long startSimNanos() {
        return started ? startSim : simNanos();
    }

    public long elapsedMillis() {
        if (!started) return 0;
        long end = ended ? endSim : simNanos();
        return TimeUnit.NANOSECONDS.toMillis(end - startSim);
    }
}
