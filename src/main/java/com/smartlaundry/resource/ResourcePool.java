package com.smartlaundry.resource;

import com.smartlaundry.model.MachineView;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bounded pool of physical resources.
 *
 * A fair semaphore controls how many customers may own resources concurrently.
 * A blocking queue identifies the actual idle machine, because a permit alone
 * does not say which physical machine is available.
 */
public final class ResourcePool<T extends LaundryResource> {
    private final List<T> all;
    private final Semaphore permits;
    private final BlockingQueue<T> idle;
    private final Queue<Integer> waitingIds = new ConcurrentLinkedQueue<>();

    private final AtomicInteger waiting = new AtomicInteger();
    private final AtomicInteger peakWaiting = new AtomicInteger();
    private final AtomicInteger inUse = new AtomicInteger();
    private final AtomicInteger peakInUse = new AtomicInteger();
    private final AtomicInteger violations = new AtomicInteger();

    public ResourcePool(List<T> resources) {
        this.all = List.copyOf(resources);
        this.permits = new Semaphore(all.size(), true);
        this.idle = new LinkedBlockingQueue<>(all);
    }

    /** Blocks until a resource is available, then returns it exclusively to customerId. */
    public T acquire(int customerId) throws InterruptedException {
        waitingIds.add(customerId);
        int nowWaiting = waiting.incrementAndGet();
        peakWaiting.accumulateAndGet(nowWaiting, Math::max);

        try {
            permits.acquire();
        } finally {
            waitingIds.remove(customerId);
            waiting.decrementAndGet();
        }

        T resource = idle.poll();
        if (resource == null) {
            permits.release();
            throw new IllegalStateException("Semaphore permit granted without an idle resource");
        }

        if (!resource.claim(customerId)) {
            violations.incrementAndGet();
            idle.offer(resource);
            permits.release();
            throw new IllegalStateException(
                    resource.getName() + " is already owned by Customer #" + resource.currentOwner());
        }

        int inUseNow = inUse.incrementAndGet();
        peakInUse.accumulateAndGet(inUseNow, Math::max);
        return resource;
    }

    /** Normal return to the idle pool. */
    public void release(T resource) {
        inUse.decrementAndGet();
        resource.markAvailable();
        idle.offer(resource);       // resource first ...
        permits.release();          // ... permit second
    }

    /** Resource failed while in use. Its permit remains unavailable until recovery. */
    public void markFailed(T resource) {
        inUse.decrementAndGet();
        resource.markFailed();
    }

    /** Recovery completes: return both the resource and its held permit. */
    public void recover(T resource) {
        resource.markAvailable();
        idle.offer(resource);
        permits.release();
    }

    /** Congested Mode: remove all kiosks/resources before customers start. */
    public List<T> takeAllOffline() {
        List<T> offline = new ArrayList<>();
        T resource;
        while ((resource = idle.poll()) != null) {
            if (!permits.tryAcquire()) {
                idle.offer(resource);
                break;
            }
            resource.markFailed();
            offline.add(resource);
        }
        return offline;
    }

    /** Restore resources previously taken offline. */
    public void restore(List<T> resources) {
        resources.forEach(this::recover);
    }

    /** Clean shutdown state after Stop; only call after customer threads have ended. */
    public void restoreAll() {
        all.forEach(LaundryResource::markAvailable);
        idle.clear();
        idle.addAll(all);
        permits.drainPermits();
        permits.release(all.size());
        waitingIds.clear();
        waiting.set(0);
        inUse.set(0);
    }

    public List<MachineView> views(long nowNanos) {
        return all.stream().map(resource -> resource.view(nowNanos)).toList();
    }

    public List<T> all() { return all; }
    public List<Integer> waitingSnapshot() { return new ArrayList<>(waitingIds); }
    public int capacity() { return all.size(); }
    public int waitingCount() { return waiting.get(); }
    public int peakWaiting() { return peakWaiting.get(); }
    public int inUse() { return inUse.get(); }
    public int peakInUse() { return peakInUse.get(); }
    public int violations() { return violations.get(); }
    public int availablePermits() { return permits.availablePermits(); }
    public int idleCount() { return idle.size(); }
}
