# Implementation and Testing Notes

## 1. Architecture

The application is a Spring Boot + Maven web application. `SimulationController` exposes REST controls and a Server-Sent Events stream. `SimulationService` owns the application-level simulation state machine. Each `LaundrySimulation` instance represents one run and owns the run-specific resources, customer threads, pause controller and failure injector. `EventService` and `StatisticsService` provide thread-safe shared telemetry.

The package structure mirrors the supplied Smart Laundry sample while the browser interface is independently designed. The production simulation uses the assignment timings directly; there is no artificial minimum-runtime sleep.

## 2. Required simulation rules

- 50 customers are created, and each customer is wrapped in its own Java `Thread`.
- Customer arrival gaps are random from 0–3 seconds.
- Six washers perform 4–6 second wash cycles.
- Four dryers perform 3–5 second dry cycles.
- Two kiosks perform 1–2 second payment cycles.
- A customer releases a resource before requesting the next resource.
- Washer and payment failures use independent 5% probabilities in the default `RANDOM` failure setting.
- A washer failure happens during the cycle; the failed washer is removed from the available pool until recovery and the customer retries.
- A payment failure causes a 2-second customer retry delay.
- Congested Mode starts with both kiosks unavailable. The owner is called once 30 customers are waiting for payment and repairs both kiosks after the configured response delay.

## 3. Concurrency mechanisms

### Fair semaphores and blocking queues

`ResourcePool<T>` uses a fair `Semaphore` for capacity and a `BlockingQueue<T>` for the actual physical resource objects. A customer blocks in `acquire()` when no permit is available; it does not busy-wait.

### Exclusive machine ownership

Each physical resource uses an atomic owner field. `compareAndSet(0, customerId)` verifies that only one customer can own a machine at a time. State changes used by the GUI are synchronized on the resource object.

### Atomic statistics

Shared counters use `AtomicInteger` or `LongAdder`. Peak usage is updated atomically, so the maximum number of concurrent washers and dryers is calculated from real execution rather than hard-coded.

### Pause and interruption

`PauseController` uses a `ReentrantLock` and `Condition` to suspend simulation work without a spin loop. `Stop` interrupts customer threads and the auxiliary run tasks, then restores the resource pools.

### Deadlock avoidance

A customer holds at most one physical resource. The washer is returned before waiting for a dryer, and the dryer is returned before waiting for a kiosk. This removes the resource hold-and-wait chain that could otherwise form a circular wait.

## 4. Two operating modes

**Normal** keeps all resources available at the beginning and runs the standard customer flow with the configured random failure probability.

**Congested** takes both payment kiosks offline before customers start. Customers can still wash and dry, so payment demand accumulates. When the payment wait count reaches 30, an atomic owner trigger ensures that the owner is called only once. After the owner response delay, both kiosks are restored.

Failure testing is separate from these two operating modes. `RANDOM` is the assignment behaviour; `FORCE_WASH` and `FORCE_PAYMENT` are deterministic test options used to demonstrate the retry paths.

## 5. Verification strategy

The JUnit test suite uses scaled timings only inside tests. This keeps automated verification fast while preserving the same resource-allocation, failure, retry, statistics and congestion logic used by the default run. Tests cover customer count, resource capacity, workflow ordering, forced washer failure and recovery, forced payment retry delay, statistics, unique resource allocation, stop/reset/restart, congestion owner handling and customer-thread event attribution.

The real submission run uses Maven and the default assignment timings:

```bash
mvn clean test
mvn spring-boot:run
```

Open `http://localhost:8080` and select either **Normal** or **Congested** before pressing **Start**.
