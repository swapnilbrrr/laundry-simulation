# Individual Assignment — Report (20%)

> Draft skeleton mapped to the rubric. Sections 1–4 are exactly the four rubric
> questions (Introduction & background 20%, Safety aspects 30%, Justification of
> coding techniques 30%, Depth of concurrency discussion 20%).
> Replace `<<NAME / STUDENT ID / DATE>>` and insert your own screenshots/logs.

---

## 1. Introduction and background (20% of the report mark)

### 1.1 What was built
A discrete-event, multi-threaded simulation of a smart self-service laundry. The
facility contains six washing machines, four tumble dryers and two payment
kiosks. Fifty customers arrive during the session; each one is a real `Thread`
in the Java Virtual Machine. A customer moves through three stages — wash,
dry, pay — and must wait for a free device whenever a stage is fully occupied.
The simulation ends when the fiftieth customer has paid, and a statistical
summary is printed.

### 1.2 Flow of activities
```
Customer thread:  arrive -> [washer queue] -> wash 4-6s
                       -> [dryer queue]  -> dry 3-5s
                       -> [kiosk queue]  -> pay 1-2s -> leave (recorded in Stats)
Main thread:      create customer -> start thread -> sleep 0-3s -> repeat x50
                  -> join() every thread -> print statistics
Repair thread:    waits 1.5s -> marks device healthy -> returns it to the pool
```
Entry gates (washer/dryer/kiosk allocation), service events and exit gates are
all printed with the name of the thread that produced them, so the reader can
verify that no thread ever acts on behalf of another customer.

### 1.3 Assumptions
1. One customer occupies exactly one device per stage, exclusively for the
   duration of that stage; a customer never holds two washers.
2. Washing 4–6s, drying 3–5s, payment 1–2s, inter-arrival 0–3s — all uniform,
   drawn from `ThreadLocalRandom` (per brief).
3. A washer fails with probability 5% per wash attempt. A failed washer is taken
   out of service for 1.5s and the affected customer rejoins the washer queue.
4. A kiosk fails with probability 5% per payment attempt; the customer waits 2s
   and tries again (any kiosk, not necessarily the same one).
5. "Total time per customer" is measured from arrival to payment completion;
   queue waiting counts as part of the customer's time.
6. Devices recover; nothing is permanently broken except in the bonus scenario.
7. The run is a fixed cohort of 50 customers, not a continuous open shop; the
   session therefore closes when the cohort is served (~85–105s, matching the
   brief's "1–2 minutes").

## 2. Safety aspects of the multi-threaded system (30%)

*Safety here means freedom from data races, lost updates, deadlocks and resource
leaks — not physical laundry safety.*

### 2.1 Which data needed monitoring, and why

| Shared data | Why it is a hazard | How it is protected |
|---|---|---|
| Device occupancy (is washer 3 free?) | Two customers taking the same washer is the core race: both "see" it free, both start a 5s cycle, capacity is silently exceeded. | `Semaphore` with one permit per device + `ArrayBlockingQueue` of idle devices. A permit plus a device object can be held by only one thread. |
| `Machine.busy`, `Machine.failed` | Read by the GUI/monitor thread while a customer writes it. Unsynchronised reads may be stale or, for non-volatile fields, never updated. | Written only inside `synchronized` methods; declared `volatile` for lock-free observation. |
| Statistics counters | `count++` is read-modify-write: two threads can both read 7 and both write 8, losing an update. | `AtomicInteger` / `AtomicLong`; peaks use `accumulateAndGet(current, Math::max)` which is atomic. |
| Peak concurrent usage | "check-then-act": `if (n > max) max = n` can interleave. | Single atomic call, no window for interference. |
| Payment queue length (bonus trigger) | Several threads cross the threshold simultaneously; naively each spawns an "owner", or a memory-read thread sees a stale value and none do. | `AtomicBoolean.compareAndSet(false, true)` — exactly one winner, lock-free. |
| Console output | Interleaved partial lines make the run unverifiable. | All printing through `synchronized` methods of one `Logger`. |
| Semaphore permits | A thread that throws between `acquire()` and `release()` leaks a permit; capacity then shrinks permanently and the run eventually deadlocks. | Every acquisition is paired with `release()` in a `finally` block; `acquire()` also rolls the permit back if interrupted while taking a device. |

### 2.2 Deadlock / starvation reasoning
- Each customer holds **at most one permit per stage**, and stages are strictly
  ordered (wash → dry → pay), so the wait-for graph is a DAG — no cycle, hence no
  deadlock.
- Permits are always returned (see table above), so a waiting customer is
  guaranteed a future release; capacity can only shrink temporarily through the
  1.5s repair window.
- All three stage semaphores are constructed with `fair = true`, so the queue is
  served approximately FIFO and a customer cannot be starved indefinitely by
  newcomers.
- Blocking waits use `Semaphore.acquire()`/`Queue.take()`, not `while(!ready)
  sleep()`: no busy waiting, and no lost-wakeup class of bug.

### 2.3 Interrupt safety
`InterruptedException` is never swallowed. `Customer.run()` logs the abandonment,
restores the interrupt flag, and every `finally` path still returns the device
and permit, so an interrupted customer leaves the system consistent.

## 3. Justification of the coding techniques implemented (30%)

**Why semaphore + blocking queue instead of `synchronized` on the shop object.**
`synchronized(laundry)` around a stage would give mutual exclusion but only one
customer could wash at a time — the simulation would lose the parallelism the
brief requires (additional requirement 4). A counting semaphore expresses the
real constraint, "*n* of these resources, any *n* threads may proceed", and is
the canonical Java facility for a resource pool.

**Why the device object is handed over a `BlockingQueue` as well.**
The semaphore alone says "some washer is free" but not *which*. Pairing it with a
queue of actual `Machine` objects gives a safe producer–consumer hand-off: `take()`
blocks until a device exists and `put()` publishes it with a happens-before edge,
so the receiving thread sees a fully initialised, non-busy device.

**Why the random failure is decided inside the device, not inside the customer.**
Keeping `failureScheduled` consumption inside `Machine.runCycle()` (a critical
section) means the decision, the busy flag and the cycle time are updated as one
indivisible unit — no thread can observe "failed but not busy" or similar
impossible intermediate states.

**Why the withdrawal of a failed washer.**
Returning a failed machine to the idle pool would let the next customer take a
device still marked `failed`; the visible state and the real state diverge.
Holding the permit until the repair thread restores it models a genuine capacity
loss — which is also why the maximum-concurrent statistic is meaningful.

**Why one thread per customer, not a thread pool.**
The brief defines a customer as a thread; 50 threads is well within JVM limits
and each has its own stack and lifecycle, making the mapping between model and
code exact. An `ExecutorService` would introduce queueing that is not in the
domain.

**Why `ThreadLocalRandom`.**
`Random` is thread-safe but internally CAS-contended; `ThreadLocalRandom` is
designed for per-thread use in concurrent code and avoids that contention.

**Why `join()` rather than sleeping then printing.**
`Thread.join()` blocks the main thread until the customer thread is provably
finished. Any fixed sleep is a guess that would print statistics for a partially
served shop.

**Code organisation.** Domain state lives only in `Laundry`; `Customer` is a thin
thread wrapper that cannot reach into other customers' state; `Stats` has no
locks at all; `Logger` is the single serialisation point. That separation is why
the concurrency policy can be audited in one file.

## 4. Depth of discussion of concurrency concepts (20%)

| Concept | Where implemented |
|---|---|
| Thread creation & lifecycle | `Simulation.run()`, `Customer` |
| Race condition & critical section | `Machine.runCycle()` (`synchronized`) |
| Counting semaphore / bounded concurrency | `Laundry` fields, `acquire()` |
| Mutual exclusion | permit + idle-pool pair, `Logger` monitors |
| Producer–consumer | `ArrayBlockingQueue` of idle devices |
| Atomic statements / lock-free | `Stats` (`incrementAndGet`, `addAndGet`) |
| Compare-and-set | `ownerCalled` guard in the bonus scenario |
| Volatile visibility | `busy`, `failed`, `kiosksDown`, `paymentQueueListener` |
| Bounded wait / blocking (no busy-wait) | `acquire`, `take`, `join` |
| Exception-safe resource release | `finally` around every stage |
| Thread confinement | Swing EDT-only UI updates in `LaundromatPanel` |
| Starvation & fairness | fair semaphores, ordered stage acquisition |

### 4.1 Code sample — bounded concurrency with a semaphore (Laundry.java)
```java
private final Semaphore washPermits = new Semaphore(WASHERS, true);
private final BlockingQueue<Machine> idleWashers = new ArrayBlockingQueue<>(WASHERS);

private Machine acquire(Semaphore permits, BlockingQueue<Machine> pool) throws InterruptedException {
    permits.acquire();                     // blocks when all 6 washers are taken
    try {
        return pool.take();                // blocks until a device object is published
    } catch (InterruptedException e) {
        permits.release();                 // roll back: never lose a permit
        throw e;
    }
}
```

### 4.2 Code sample — the critical section (Machine.java)
```java
public synchronized boolean runCycle(String taskName, int millis, Logger log)
        throws InterruptedException {
    busy = true;                                   // only reachable while holding this monitor
    log.info(label(), taskName + " started for " + Thread.currentThread().getName());
    try {
        Thread.sleep(millis);                      // the simulated service time
    } finally {
        busy = false;
    }
    if (failureScheduled) { failureScheduled = false; failed = true; return false; }
    return true;
}
```

### 4.3 Code sample — atomic peak tracking (Stats.java)
```java
// NOT thread-safe would be:  if (now > peak) peak = now;   (check-then-act race)
peakWashersInUse.accumulateAndGet(now, Math::max);          // one atomic operation
served.incrementAndGet();                                   // no lost updates
totalSeconds.addAndGet(durationMillis);
```

### 4.4 Code sample — exception-safe stage (Laundry.wash)
```java
while (true) {
    Machine washer = acquire(washPermits, idleWashers);
    boolean withdrawnForRepair = false;
    try { /* ... 4-6s cycle ... */ }
    finally {
        if (!withdrawnForRepair) {          // success, failure or interrupt all land here
            idleWashers.put(washer);
            washPermits.release();
        }
    }
}
```

### 4.5 Code sample — one-shot CAS trigger (Simulation.java)
```java
if (q >= Laundry.OWNER_CALLED_AT && ownerCalled.compareAndSet(false, true)) {
    // exactly one thread can win this race, so exactly one owner is dispatched
}
```

## 5. Requirements list (for the deliverable checklist)

**Met — basic:** random 0–3s arrivals; one thread per customer; washing 4–6s
with waiting; drying 3–5s with waiting; payment 1–2s at two kiosks.
**Met — additional:** mutual exclusion via `Semaphore`/`synchronized`/blocking
queue; 5% washer mid-cycle failure with retry; 5% kiosk failure with 2s retry;
statistics (served, average total time, max concurrent washers and dryers);
simultaneous machines visible in the log; session length inside the 1–2 minute
budget.
**Met — bonus:** congested scenario with both kiosks down and the owner called in
after 30 queued customers; Swing GUI visualisation.
**Not met / out of scope:** continuous (non-cohort) customer flow; persistence of
statistics; interactive GUI; weighted priority queues (FIFO fairness only).

## 6. Testing summary
See `docs/TESTING.md` — ten test cases with results, plus the two defects found
and fixed during testing (the bonus-scenario livelock and the failed-device pool
bug), and the captured logs in `logs/`.
